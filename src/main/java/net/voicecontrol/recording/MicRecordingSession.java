package net.voicecontrol.recording;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import net.voicecontrol.Config;
import net.voicecontrol.VoiceControlVoicePlugin;
import net.voicecontrol.logging.AdminLogger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class MicRecordingSession implements Runnable {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final byte[] POISON_PILL = new byte[0];

    private final UUID playerUuid;
    private final String playerNick;
    private final String startedBy;
    private final String startedAtIso;
    private final String timestamp;
    private final long startTimeMs;

    private final LinkedBlockingQueue<byte[]> packetQueue = new LinkedBlockingQueue<>();
    private final AudioEncoderWrapper encoder;
    private final OpusDecoder decoder;
    private final File audioFile;
    
    private Thread workerThread;
    private volatile boolean running = true;
    private long stoppedTimeMs = 0;

    public MicRecordingSession(UUID playerUuid, String playerNick, String startedBy) throws IOException {
        this.playerUuid = playerUuid;
        this.playerNick = playerNick;
        this.startedBy = startedBy;
        this.startTimeMs = System.currentTimeMillis();
        
        LocalDateTime now = LocalDateTime.now();
        this.timestamp = now.format(DATE_TIME_FORMATTER);
        this.startedAtIso = now.format(ISO_FORMATTER);

        VoicechatServerApi api = VoiceControlVoicePlugin.getServerApi();
        if (api == null) {
            throw new IOException("Voice chat API is not loaded.");
        }

        this.decoder = api.createDecoder();
        if (this.decoder == null) {
            throw new IOException("Failed to create Opus Decoder (native libraries unavailable).");
        }

        String format = Config.SERVER.recordingDefaultFormat.get().toLowerCase();
        boolean requestMp3 = "mp3".equals(format);
        
        // Output base path without extension
        File baseFile = RecordingStorage.getMicFileBase(playerNick, timestamp);
        
        // Initialize encoder (will fall back to WAV if MP3 is unavailable and resolve file name)
        this.encoder = new AudioEncoderWrapper(baseFile, requestMp3);
        this.audioFile = encoder.getOutputFile();

        // Start worker thread
        this.workerThread = new Thread(this, "VoiceControl-MicRec-" + playerNick);
        this.workerThread.start();

        AdminLogger.info(startedBy, "Started direct mic recording for player " + playerNick + " (" + playerUuid + "). Format: " + (encoder.isMp3() ? "MP3" : "WAV"));
    }

    public void processPacket(byte[] opusData) {
        if (!running) return;

        // Check max duration limit
        int maxMinutes = Config.SERVER.recordingMaxRecordingMinutes.get();
        if (maxMinutes > 0) {
            long elapsedMs = System.currentTimeMillis() - startTimeMs;
            if (elapsedMs >= (long) maxMinutes * 60 * 1000) {
                AdminLogger.info("SYSTEM", "Mic recording for " + playerNick + " hit maximum duration limit of " + maxMinutes + " minutes. Auto-stopping.");
                stop();
                return;
            }
        }

        packetQueue.offer(opusData);
    }

    public void stop() {
        if (!running) return;
        running = false;
        stoppedTimeMs = System.currentTimeMillis();
        packetQueue.offer(POISON_PILL); // Signal worker to finish
        if (workerThread != null && Thread.currentThread() != workerThread) {
            try {
                workerThread.join(5000); // Wait up to 5 seconds for finalization
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                byte[] opusData = packetQueue.poll(1, TimeUnit.SECONDS);
                if (opusData == null) {
                    if (!running && packetQueue.isEmpty()) {
                        break;
                    }
                    continue;
                }
                
                if (opusData == POISON_PILL) {
                    break;
                }

                try {
                    short[] decoded = decoder.decode(opusData);
                    if (decoded != null && decoded.length > 0) {
                        encoder.write(decoded);
                    }
                } catch (Exception e) {
                    AdminLogger.error("SYSTEM", "Error decoding/encoding mic frame for player " + playerNick + ": " + e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        try {
            if (decoder != null) {
                decoder.close();
            }
            if (encoder != null) {
                encoder.close();
            }
        } catch (IOException e) {
            AdminLogger.error("SYSTEM", "Error closing mic recording streams for " + playerNick + ": " + e.getMessage());
        }

        long actualStoppedTime = stoppedTimeMs > 0 ? stoppedTimeMs : System.currentTimeMillis();
        double durationSeconds = (actualStoppedTime - startTimeMs) / 1000.0;
        String stoppedAtIso = LocalDateTime.now().format(ISO_FORMATTER);

        String sha256 = "";
        if (Config.SERVER.recordingSaveHash.get()) {
            sha256 = RecordingStorage.calculateSHA256(audioFile);
            File hashFile = RecordingStorage.getMicHashFile(playerNick, timestamp, "sha256");
            try (FileWriter fw = new FileWriter(hashFile)) {
                fw.write(sha256);
            } catch (IOException e) {
                AdminLogger.error("SYSTEM", "Failed to write mic SHA-256 hash file: " + e.getMessage());
            }
        }

        if (Config.SERVER.recordingSaveMetadata.get()) {
            File jsonFile = RecordingStorage.getMicMetadataFile(playerNick, timestamp);
            String formatStr = encoder.getFormatExtension();
            RecordingStorage.writeMetadata(
                    jsonFile,
                    "mic",
                    playerNick,
                    null,
                    playerUuid,
                    playerNick,
                    startedBy,
                    startedAtIso,
                    stoppedAtIso,
                    durationSeconds,
                    formatStr,
                    audioFile.getAbsolutePath(),
                    sha256
            );
        }

        AdminLogger.info(startedBy, "Stopped direct mic recording for player " + playerNick + ". Duration: " + String.format("%.2f", durationSeconds) + "s");
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerNick() {
        return playerNick;
    }

    public boolean isRunning() {
        return running;
    }

    public double getElapsedSeconds() {
        long current = running ? System.currentTimeMillis() : stoppedTimeMs;
        return (current - startTimeMs) / 1000.0;
    }

    public File getAudioFile() {
        return audioFile;
    }
}
