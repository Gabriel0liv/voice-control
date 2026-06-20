package net.voicecontrol.audio;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.*;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.voicecontrol.Config;
import net.voicecontrol.VoiceControlMod;
import net.voicecontrol.VoiceControlVoicePlugin;
import net.voicecontrol.logging.AdminLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class VoiceChatPlaybackEngine {

    private static final Map<String, short[]> pcmCache = new ConcurrentHashMap<>();
    private static final Map<String, List<AudioPlayer>> activePlayers = new ConcurrentHashMap<>();

    public static void clearCache() {
        pcmCache.clear();
    }

    public static void preDecode(AudioImportManager.AudioEntry entry) {
        if (Config.SERVER.voicePlaybackPreDecodePcmOnReload.get()) {
            getOrDecodePCM(entry);
        }
    }

    public static short[] getOrDecodePCM(AudioImportManager.AudioEntry entry) {
        if (entry == null) return null;
        return pcmCache.computeIfAbsent(entry.id, id -> decodeToPcm(entry.file));
    }

    public static short[] decodeToPcm(File inputFile) {
        String sha256 = AudioImportManager.calculateSHA256(inputFile);
        if (sha256.isEmpty()) return null;

        File rawFile = VoiceControlMod.getBaseFolder().resolve("transcoded-cache/" + sha256 + ".raw").toFile();
        if (rawFile.exists()) {
            return readRawPcm(rawFile);
        }

        if (!OggConverter.isFFmpegAvailable()) {
            AdminLogger.error("SYSTEM", "FFmpeg is not available to decode audio to PCM for Voice Chat: " + inputFile.getName());
            return null;
        }

        String path = Config.SERVER.audioLibraryFfmpegPath.get();
        String command = (path == null || path.isEmpty()) ? "ffmpeg" : path;

        try {
            File parentDir = rawFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            ProcessBuilder pb = new ProcessBuilder(
                    command,
                    "-y",
                    "-i",
                    inputFile.getAbsolutePath(),
                    "-f",
                    "s16le",
                    "-acodec",
                    "pcm_s16le",
                    "-ar",
                    "48000",
                    "-ac",
                    "1",
                    rawFile.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (reader.readLine() != null) {}
            }

            int exitCode = p.waitFor();
            if (exitCode == 0 && rawFile.exists()) {
                return readRawPcm(rawFile);
            } else {
                AdminLogger.error("SYSTEM", "FFmpeg failed to decode " + inputFile.getName() + " to PCM. Exit code: " + exitCode);
                return null;
            }
        } catch (Exception e) {
            AdminLogger.error("SYSTEM", "Failed to decode " + inputFile.getName() + " to PCM via FFmpeg: " + e.getMessage());
            return null;
        }
    }

    private static short[] readRawPcm(File rawFile) {
        try {
            byte[] bytes = Files.readAllBytes(rawFile.toPath());
            short[] shorts = new short[bytes.length / 2];
            for (int i = 0; i < shorts.length; i++) {
                int b1 = bytes[i * 2] & 0xFF;
                int b2 = bytes[i * 2 + 1] & 0xFF;
                shorts[i] = (short) ((b2 << 8) | b1);
            }
            return shorts;
        } catch (IOException e) {
            AdminLogger.error("SYSTEM", "Failed to read raw PCM file " + rawFile.getName() + ": " + e.getMessage());
            return null;
        }
    }

    public static boolean playStatic(String soundId, List<ServerPlayer> targets, CommandSourceStack source) {
        if (!OggConverter.isFFmpegAvailable()) {
            source.sendFailure(Component.literal("§c[VoiceControl] Voiceplay precisa de FFmpeg no servidor para converter áudio para PCM 48kHz mono. Configure audioLibrary.ffmpegPath ou instale ffmpeg."));
            return false;
        }

        VoicechatServerApi api = VoiceControlVoicePlugin.getServerApi();
        if (api == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Simple Voice Chat API não inicializada."));
            return false;
        }

        AudioImportManager.AudioEntry entry = AudioImportManager.getImportedSounds().get(soundId);
        if (entry == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Som '" + soundId + "' não encontrado na biblioteca."));
            return false;
        }

        short[] pcm = getOrDecodePCM(entry);
        if (pcm == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Falha ao decodificar áudio '" + soundId + "' para PCM."));
            return false;
        }

        UUID channelId = UUID.randomUUID();
        StaticAudioChannel channel = api.createStaticAudioChannel(channelId);
        if (channel == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Falha ao criar canal de áudio estático."));
            return false;
        }

        int targetCount = 0;
        for (ServerPlayer target : targets) {
            VoicechatConnection conn = api.getConnectionOf(target.getUUID());
            if (conn != null) {
                channel.addTarget(conn);
                targetCount++;
            }
        }

        if (targetCount == 0) {
            source.sendFailure(Component.literal("§c[VoiceControl] Nenhum dos alvos está conectado ao Simple Voice Chat."));
            return false;
        }

        OpusEncoder encoder = api.createEncoder();
        AudioPlayer player = api.createAudioPlayer(channel, encoder, new PcmAudioSupplier(pcm));
        
        registerActivePlayer(soundId, player);
        player.setOnStopped(() -> {
            encoder.close();
            removeActivePlayer(soundId, player);
        });

        player.startPlaying();
        return true;
    }

    public static boolean playLocational(String soundId, ServerLevel level, double x, double y, double z, CommandSourceStack source) {
        if (!OggConverter.isFFmpegAvailable()) {
            source.sendFailure(Component.literal("§c[VoiceControl] Voiceplay precisa de FFmpeg no servidor para converter áudio para PCM 48kHz mono. Configure audioLibrary.ffmpegPath ou instale ffmpeg."));
            return false;
        }

        VoicechatServerApi api = VoiceControlVoicePlugin.getServerApi();
        if (api == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Simple Voice Chat API não inicializada."));
            return false;
        }

        AudioImportManager.AudioEntry entry = AudioImportManager.getImportedSounds().get(soundId);
        if (entry == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Som '" + soundId + "' não encontrado na biblioteca."));
            return false;
        }

        short[] pcm = getOrDecodePCM(entry);
        if (pcm == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Falha ao decodificar áudio '" + soundId + "' para PCM."));
            return false;
        }

        UUID channelId = UUID.randomUUID();
        Position position = api.createPosition(x, y, z);
        LocationalAudioChannel channel = api.createLocationalAudioChannel(channelId, api.fromServerLevel(level), position);
        if (channel == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Falha ao criar canal de áudio posicional."));
            return false;
        }

        channel.setDistance((float) Config.SERVER.voicePlaybackDefaultDistance.get());

        OpusEncoder encoder = api.createEncoder();
        AudioPlayer player = api.createAudioPlayer(channel, encoder, new PcmAudioSupplier(pcm));
        
        registerActivePlayer(soundId, player);
        player.setOnStopped(() -> {
            encoder.close();
            removeActivePlayer(soundId, player);
        });

        player.startPlaying();
        return true;
    }

    public static boolean playEntity(String soundId, Entity entity, CommandSourceStack source) {
        if (!OggConverter.isFFmpegAvailable()) {
            source.sendFailure(Component.literal("§c[VoiceControl] Voiceplay precisa de FFmpeg no servidor para converter áudio para PCM 48kHz mono. Configure audioLibrary.ffmpegPath ou instale ffmpeg."));
            return false;
        }

        VoicechatServerApi api = VoiceControlVoicePlugin.getServerApi();
        if (api == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Simple Voice Chat API não inicializada."));
            return false;
        }

        AudioImportManager.AudioEntry entry = AudioImportManager.getImportedSounds().get(soundId);
        if (entry == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Som '" + soundId + "' não encontrado na biblioteca."));
            return false;
        }

        short[] pcm = getOrDecodePCM(entry);
        if (pcm == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Falha ao decodificar áudio '" + soundId + "' para PCM."));
            return false;
        }

        UUID channelId = UUID.randomUUID();
        EntityAudioChannel channel = api.createEntityAudioChannel(channelId, api.fromEntity(entity));
        if (channel == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Falha ao criar canal de áudio da entidade."));
            return false;
        }

        channel.setDistance((float) Config.SERVER.voicePlaybackDefaultDistance.get());

        OpusEncoder encoder = api.createEncoder();
        AudioPlayer player = api.createAudioPlayer(channel, encoder, new PcmAudioSupplier(pcm));
        
        registerActivePlayer(soundId, player);
        player.setOnStopped(() -> {
            encoder.close();
            removeActivePlayer(soundId, player);
        });

        player.startPlaying();
        return true;
    }



    public static boolean stopSound(String soundId) {
        List<AudioPlayer> players = activePlayers.get(soundId);
        if (players != null && !players.isEmpty()) {
            List<AudioPlayer> toStop;
            synchronized (players) {
                toStop = new ArrayList<>(players);
                players.clear();
            }
            for (AudioPlayer player : toStop) {
                try {
                    player.stopPlaying();
                } catch (Exception ignored) {}
            }
            activePlayers.remove(soundId);
            return true;
        }
        return false;
    }

    public static void stopAll() {
        List<AudioPlayer> toStop = new ArrayList<>();
        for (List<AudioPlayer> players : activePlayers.values()) {
            if (players != null) {
                synchronized (players) {
                    toStop.addAll(players);
                    players.clear();
                }
            }
        }
        activePlayers.clear();
        for (AudioPlayer player : toStop) {
            try {
                player.stopPlaying();
            } catch (Exception ignored) {}
        }
    }

    private static void registerActivePlayer(String soundId, AudioPlayer player) {
        activePlayers.computeIfAbsent(soundId, id -> Collections.synchronizedList(new ArrayList<>())).add(player);
    }

    private static void removeActivePlayer(String soundId, AudioPlayer player) {
        List<AudioPlayer> players = activePlayers.get(soundId);
        if (players != null) {
            players.remove(player);
            if (players.isEmpty()) {
                activePlayers.remove(soundId);
            }
        }
    }

    private static class PcmAudioSupplier implements Supplier<short[]> {
        private final short[] pcmData;
        private int position = 0;
        private final int maxDurationSamples;

        public PcmAudioSupplier(short[] pcmData) {
            this.pcmData = pcmData;
            // Cap duration based on configuration
            int maxSeconds = Config.SERVER.voicePlaybackMaxDurationSeconds.get();
            this.maxDurationSamples = Math.min(pcmData.length, maxSeconds * 48000);
        }

        @Override
        public short[] get() {
            if (position >= maxDurationSamples) {
                return null;
            }
            int remaining = maxDurationSamples - position;
            int frameSize = Math.min(960, remaining);
            short[] frame = new short[960];
            System.arraycopy(pcmData, position, frame, 0, frameSize);
            position += 960;
            return frame;
        }
    }
}
