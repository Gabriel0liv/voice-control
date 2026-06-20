package net.voicecontrol.recording;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiolistener.PlayerAudioListener;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.voicecontrol.Config;
import net.voicecontrol.VoiceControlVoicePlugin;
import net.voicecontrol.logging.AdminLogger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class MonitorRecordingSession implements Runnable {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final QueuedPacket POISON_PILL = new QueuedPacket(null, null, 0L);

    private final MinecraftServer server;
    private final UUID monitorUuid;
    private final String monitorNick;
    private final String startedBy;
    private final String startedAtIso;
    private final String timestamp;
    private final long startTimeMs;

    private final LinkedBlockingQueue<QueuedPacket> packetQueue = new LinkedBlockingQueue<>();
    private final AudioEncoderWrapper mixEncoder;
    private final File mixAudioFile;
    
    // Multi-speaker tracking
    private final Map<UUID, OpusDecoder> decoders = new HashMap<>();
    private final Map<UUID, SpeakerTrack> speakerTracks = new HashMap<>();

    // Real-time mixing sliding window buffer (2 seconds)
    private final short[] mixedBuffer = new short[96000];
    private long bufferStartSample = 0;

    private PlayerAudioListener audioListener;
    private Thread workerThread;
    private volatile boolean running = true;
    private long stoppedTimeMs = 0;

    private static class QueuedPacket {
        final UUID speakerUuid;
        final byte[] opusData;
        final long timestampMs;

        QueuedPacket(UUID speakerUuid, byte[] opusData, long timestampMs) {
            this.speakerUuid = speakerUuid;
            this.opusData = opusData;
            this.timestampMs = timestampMs;
        }
    }

    public MonitorRecordingSession(MinecraftServer server, UUID monitorUuid, String monitorNick, String startedBy) throws IOException {
        this.server = server;
        this.monitorUuid = monitorUuid;
        this.monitorNick = monitorNick;
        this.startedBy = startedBy;
        this.startTimeMs = System.currentTimeMillis();

        LocalDateTime now = LocalDateTime.now();
        this.timestamp = now.format(DATE_TIME_FORMATTER);
        this.startedAtIso = now.format(ISO_FORMATTER);

        VoicechatServerApi api = VoiceControlVoicePlugin.getServerApi();
        if (api == null) {
            throw new IOException("Voice chat API is not loaded.");
        }

        String format = Config.SERVER.recordingDefaultFormat.get().toLowerCase();
        boolean requestMp3 = "mp3".equals(format);

        // Mixed output base path
        File baseFile = RecordingStorage.getMonitorFileBase(monitorNick, timestamp);
        this.mixEncoder = new AudioEncoderWrapper(baseFile, requestMp3);
        this.mixAudioFile = mixEncoder.getOutputFile();

        // Start worker thread
        this.workerThread = new Thread(this, "VoiceControl-MonRec-" + monitorNick);
        this.workerThread.start();

        // Create and register PlayerAudioListener
        try {
            this.audioListener = api.playerAudioListenerBuilder()
                    .setPlayer(monitorUuid)
                    .setPacketListener(soundPacket -> {
                        if (!running) return;
                        UUID speaker = null;
                        if (soundPacket instanceof EntitySoundPacket) {
                            speaker = ((EntitySoundPacket) soundPacket).getEntityUuid();
                        }
                        packetQueue.offer(new QueuedPacket(speaker, soundPacket.getOpusEncodedData(), System.currentTimeMillis()));
                    })
                    .build();
            api.registerAudioListener(audioListener);
        } catch (Throwable t) {
            this.running = false;
            packetQueue.offer(POISON_PILL);
            throw new IOException("Failed to build/register PlayerAudioListener: " + t.getMessage(), t);
        }

        AdminLogger.info(startedBy, "Started monitored recording for player " + monitorNick + " (" + monitorUuid + "). Format: " + (mixEncoder.isMp3() ? "MP3" : "WAV"));
    }

    public void stop() {
        if (!running) return;
        running = false;
        stoppedTimeMs = System.currentTimeMillis();

        // Unregister listener
        VoicechatServerApi api = VoiceControlVoicePlugin.getServerApi();
        if (api != null && audioListener != null) {
            try {
                api.unregisterAudioListener(audioListener.getListenerId());
            } catch (Throwable t) {
                AdminLogger.error("SYSTEM", "Failed to unregister audio listener: " + t.getMessage());
            }
        }

        packetQueue.offer(POISON_PILL); // Signal worker to finish
        if (workerThread != null && Thread.currentThread() != workerThread) {
            try {
                workerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                QueuedPacket qp = packetQueue.poll(1, TimeUnit.SECONDS);
                if (qp == null) {
                    if (!running && packetQueue.isEmpty()) {
                        break;
                    }
                    continue;
                }

                if (qp == POISON_PILL) {
                    break;
                }

                // Check duration limit
                int maxMinutes = Config.SERVER.recordingMaxRecordingMinutes.get();
                if (maxMinutes > 0) {
                    long elapsedMs = System.currentTimeMillis() - startTimeMs;
                    if (elapsedMs >= (long) maxMinutes * 60 * 1000) {
                        AdminLogger.info("SYSTEM", "Monitor recording for " + monitorNick + " hit maximum duration limit. Auto-stopping.");
                        // Stop will trigger poison pill
                        stop();
                        break;
                    }
                }

                processQueuedPacket(qp);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            cleanup();
        }
    }

    private void processQueuedPacket(QueuedPacket qp) {
        long packetTimeMs = qp.timestampMs;
        long elapsedMs = packetTimeMs - startTimeMs;
        long targetSample = (elapsedMs * 48000) / 1000;

        // Get or create decoder for speaker
        UUID speakerUuid = qp.speakerUuid;
        if (speakerUuid == null) {
            // If sender not specified, use a dummy UUID to decode it as ambient
            speakerUuid = new UUID(0, 0);
        }

        VoicechatServerApi api = VoiceControlVoicePlugin.getServerApi();
        if (api == null) return;

        OpusDecoder decoder = decoders.computeIfAbsent(speakerUuid, u -> api.createDecoder());
        if (decoder == null) return;

        try {
            short[] pcm = decoder.decode(qp.opusData);
            if (pcm == null || pcm.length == 0) return;

            // 1. Mix into mixed track
            mixAudio(targetSample, pcm);

            // 2. Write to speaker-specific track if speaker is real
            if (!speakerUuid.equals(new UUID(0, 0))) {
                SpeakerTrack track = getOrCreateSpeakerTrack(speakerUuid);
                if (track != null) {
                    track.mixAudio(targetSample, pcm);
                }
            }
        } catch (Exception e) {
            AdminLogger.error("SYSTEM", "Error processing monitored packet: " + e.getMessage());
        }
    }

    private synchronized void mixAudio(long targetSample, short[] pcm) throws IOException {
        long neededEnd = targetSample + pcm.length;
        if (neededEnd > bufferStartSample + mixedBuffer.length) {
            // Slide buffer to accommodate the new packet
            long newStart = neededEnd - mixedBuffer.length;
            slideBufferTo(newStart);
        }

        int offset = (int) (targetSample - bufferStartSample);
        for (int i = 0; i < pcm.length; i++) {
            int idx = offset + i;
            if (idx >= 0 && idx < mixedBuffer.length) {
                int sum = mixedBuffer[idx] + pcm[i];
                if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE;
                else if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE;
                mixedBuffer[idx] = (short) sum;
            }
        }
    }

    private void slideBufferTo(long newStartSample) throws IOException {
        long slideAmount = newStartSample - bufferStartSample;
        if (slideAmount <= 0) return;

        if (slideAmount >= mixedBuffer.length) {
            // Write entire buffer
            mixEncoder.write(mixedBuffer);
            Arrays.fill(mixedBuffer, (short) 0);

            // Write extra silence directly
            long extraSilence = slideAmount - mixedBuffer.length;
            if (extraSilence > 0) {
                short[] silence = new short[48000];
                while (extraSilence > 0) {
                    int toWrite = (int) Math.min(extraSilence, silence.length);
                    if (toWrite == silence.length) {
                        mixEncoder.write(silence);
                    } else {
                        mixEncoder.write(Arrays.copyOf(silence, toWrite));
                    }
                    extraSilence -= toWrite;
                }
            }
            bufferStartSample = newStartSample;
        } else {
            // Write the first slideAmount samples
            short[] toWrite = new short[(int) slideAmount];
            System.arraycopy(mixedBuffer, 0, toWrite, 0, (int) slideAmount);
            mixEncoder.write(toWrite);

            // Shift left
            System.arraycopy(mixedBuffer, (int) slideAmount, mixedBuffer, 0, (int) (mixedBuffer.length - slideAmount));
            Arrays.fill(mixedBuffer, (int) (mixedBuffer.length - slideAmount), mixedBuffer.length, (short) 0);
            
            bufferStartSample = newStartSample;
        }
    }

    private SpeakerTrack getOrCreateSpeakerTrack(UUID speakerUuid) {
        return speakerTracks.computeIfAbsent(speakerUuid, uuid -> {
            try {
                String speakerNick = uuid.toString();
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null) {
                    speakerNick = player.getGameProfile().getName();
                }

                String format = Config.SERVER.recordingDefaultFormat.get().toLowerCase();
                boolean requestMp3 = "mp3".equals(format);
                File baseFile = RecordingStorage.getSpeakerFileBase(monitorNick, speakerNick, timestamp);
                
                return new SpeakerTrack(speakerNick, baseFile, requestMp3);
            } catch (IOException e) {
                AdminLogger.error("SYSTEM", "Failed to create speaker track for " + uuid + ": " + e.getMessage());
                return null;
            }
        });
    }

    private void cleanup() {
        // Stop audio listener connection
        long actualStoppedTime = stoppedTimeMs > 0 ? stoppedTimeMs : System.currentTimeMillis();
        long elapsedMs = actualStoppedTime - startTimeMs;
        long endSample = (elapsedMs * 48000) / 1000;

        try {
            // Slide mixer buffer to end sample to write all remaining data
            slideBufferTo(endSample);
            // Write remaining buffer data
            mixEncoder.write(mixedBuffer);
        } catch (IOException e) {
            AdminLogger.error("SYSTEM", "Error flushing mix buffer: " + e.getMessage());
        }

        // Close decoders
        for (OpusDecoder decoder : decoders.values()) {
            decoder.close();
        }
        decoders.clear();

        // Close main mix encoder
        try {
            mixEncoder.close();
        } catch (IOException e) {
            AdminLogger.error("SYSTEM", "Error closing mix encoder: " + e.getMessage());
        }

        // Close speaker tracks
        for (SpeakerTrack track : speakerTracks.values()) {
            track.close(endSample);
        }
        speakerTracks.clear();

        double durationSeconds = elapsedMs / 1000.0;
        String stoppedAtIso = LocalDateTime.now().format(ISO_FORMATTER);

        String sha256 = "";
        if (Config.SERVER.recordingSaveHash.get()) {
            sha256 = RecordingStorage.calculateSHA256(mixAudioFile);
            File hashFile = RecordingStorage.getMonitorHashFile(monitorNick, timestamp, "sha256");
            try (FileWriter fw = new FileWriter(hashFile)) {
                fw.write(sha256);
            } catch (IOException e) {
                AdminLogger.error("SYSTEM", "Failed to write monitor SHA-256 hash file: " + e.getMessage());
            }
        }

        if (Config.SERVER.recordingSaveMetadata.get()) {
            File jsonFile = RecordingStorage.getMonitorMetadataFile(monitorNick, timestamp);
            String formatStr = mixEncoder.getFormatExtension();
            RecordingStorage.writeMetadata(
                    jsonFile,
                    "monitor",
                    null,
                    monitorNick,
                    monitorUuid,
                    monitorNick,
                    startedBy,
                    startedAtIso,
                    stoppedAtIso,
                    durationSeconds,
                    formatStr,
                    mixAudioFile.getAbsolutePath(),
                    sha256
            );
        }

        AdminLogger.info(startedBy, "Stopped monitored recording for player " + monitorNick + ". Duration: " + String.format("%.2f", durationSeconds) + "s");
    }

    public UUID getMonitorUuid() {
        return monitorUuid;
    }

    public String getMonitorNick() {
        return monitorNick;
    }

    public boolean isRunning() {
        return running;
    }

    public double getElapsedSeconds() {
        long current = running ? System.currentTimeMillis() : stoppedTimeMs;
        return (current - startTimeMs) / 1000.0;
    }

    public File getMixAudioFile() {
        return mixAudioFile;
    }

    // Helper class for speaker tracks
    private static class SpeakerTrack {
        private final String nick;
        private final File file;
        private final AudioEncoderWrapper encoder;
        private final short[] buffer = new short[96000];
        private long startSample = 0;

        SpeakerTrack(String nick, File baseFile, boolean requestMp3) throws IOException {
            this.nick = nick;
            this.encoder = new AudioEncoderWrapper(baseFile, requestMp3);
            this.file = this.encoder.getOutputFile();
        }

        synchronized void mixAudio(long targetSample, short[] pcm) throws IOException {
            long neededEnd = targetSample + pcm.length;
            if (neededEnd > startSample + buffer.length) {
                long newStart = neededEnd - buffer.length;
                slideBufferTo(newStart);
            }

            int offset = (int) (targetSample - startSample);
            for (int i = 0; i < pcm.length; i++) {
                int idx = offset + i;
                if (idx >= 0 && idx < buffer.length) {
                    int sum = buffer[idx] + pcm[i];
                    if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE;
                    else if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE;
                    buffer[idx] = (short) sum;
                }
            }
        }

        private void slideBufferTo(long newStartSample) throws IOException {
            long slideAmount = newStartSample - startSample;
            if (slideAmount <= 0) return;

            if (slideAmount >= buffer.length) {
                encoder.write(buffer);
                Arrays.fill(buffer, (short) 0);

                long extraSilence = slideAmount - buffer.length;
                if (extraSilence > 0) {
                    short[] silence = new short[48000];
                    while (extraSilence > 0) {
                        int toWrite = (int) Math.min(extraSilence, silence.length);
                        if (toWrite == silence.length) {
                            encoder.write(silence);
                        } else {
                            encoder.write(Arrays.copyOf(silence, toWrite));
                        }
                        extraSilence -= toWrite;
                    }
                }
                startSample = newStartSample;
            } else {
                short[] toWrite = new short[(int) slideAmount];
                System.arraycopy(buffer, 0, toWrite, 0, (int) slideAmount);
                encoder.write(toWrite);

                System.arraycopy(buffer, (int) slideAmount, buffer, 0, (int) (buffer.length - slideAmount));
                Arrays.fill(buffer, (int) (buffer.length - slideAmount), buffer.length, (short) 0);
                
                startSample = newStartSample;
            }
        }

        synchronized void close(long endSample) {
            try {
                slideBufferTo(endSample);
                encoder.write(buffer);
                encoder.close();
            } catch (IOException e) {
                AdminLogger.error("SYSTEM", "Failed to close speaker track for " + nick + ": " + e.getMessage());
            }
        }
    }
}
