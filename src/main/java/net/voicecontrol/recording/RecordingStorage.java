package net.voicecontrol.recording;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.SharedConstants;
import net.minecraftforge.fml.ModList;
import net.voicecontrol.VoiceControlMod;
import net.voicecontrol.logging.AdminLogger;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RecordingStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static File getMicFileBase(String playerNick, String timestamp) {
        Path path = VoiceControlMod.getBaseFolder().resolve("recordings/players/" + playerNick);
        try {
            Files.createDirectories(path);
        } catch (IOException ignored) {}
        return path.resolve(timestamp + "_mic").toFile();
    }

    public static File getMonitorFileBase(String monitorNick, String timestamp) {
        Path path = VoiceControlMod.getBaseFolder().resolve("recordings/monitors/" + monitorNick);
        try {
            Files.createDirectories(path);
        } catch (IOException ignored) {}
        return path.resolve(timestamp + "_monitor_mix").toFile();
    }

    public static File getSpeakerFileBase(String monitorNick, String speakerNick, String timestamp) {
        Path path = VoiceControlMod.getBaseFolder().resolve("recordings/monitors/" + monitorNick + "/speakers/" + speakerNick);
        try {
            Files.createDirectories(path);
        } catch (IOException ignored) {}
        return path.resolve(timestamp + "_seen-by-" + monitorNick).toFile();
    }

    public static File getMicFile(String playerNick, String timestamp, String format) {
        Path path = VoiceControlMod.getBaseFolder().resolve("recordings/players/" + playerNick);
        try {
            Files.createDirectories(path);
        } catch (IOException ignored) {}
        return path.resolve(timestamp + "_mic." + format).toFile();
    }

    public static File getMicMetadataFile(String playerNick, String timestamp) {
        return VoiceControlMod.getBaseFolder().resolve("recordings/players/" + playerNick + "/" + timestamp + "_mic.json").toFile();
    }

    public static File getMicHashFile(String playerNick, String timestamp, String hashType) {
        return VoiceControlMod.getBaseFolder().resolve("recordings/players/" + playerNick + "/" + timestamp + "_mic." + hashType).toFile();
    }

    public static File getMonitorFile(String monitorNick, String timestamp, String format) {
        Path path = VoiceControlMod.getBaseFolder().resolve("recordings/monitors/" + monitorNick);
        try {
            Files.createDirectories(path);
        } catch (IOException ignored) {}
        return path.resolve(timestamp + "_monitor_mix." + format).toFile();
    }

    public static File getMonitorMetadataFile(String monitorNick, String timestamp) {
        return VoiceControlMod.getBaseFolder().resolve("recordings/monitors/" + monitorNick + "/" + timestamp + "_monitor.json").toFile();
    }

    public static File getMonitorHashFile(String monitorNick, String timestamp, String hashType) {
        return VoiceControlMod.getBaseFolder().resolve("recordings/monitors/" + monitorNick + "/" + timestamp + "_monitor." + hashType).toFile();
    }

    public static File getSpeakerFile(String monitorNick, String speakerNick, String timestamp, String format) {
        Path path = VoiceControlMod.getBaseFolder().resolve("recordings/monitors/" + monitorNick + "/speakers/" + speakerNick);
        try {
            Files.createDirectories(path);
        } catch (IOException ignored) {}
        return path.resolve(timestamp + "_seen-by-" + monitorNick + "." + format).toFile();
    }

    public static String calculateSHA256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            AdminLogger.error("SYSTEM", "Failed to calculate SHA-256 for file " + file.getName() + ": " + e.getMessage());
            return "";
        }
    }

    public static void writeMetadata(File jsonFile, String mode, String targetPlayer, String monitorPlayer, UUID playerUuid, String nick, String startedBy, String startedAt, String stoppedAt, double durationSeconds, String format, String filePath, String sha256) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("mode", mode);
        if ("mic".equalsIgnoreCase(mode)) {
            metadata.put("targetPlayer", targetPlayer);
        } else {
            metadata.put("monitorPlayer", monitorPlayer);
        }
        metadata.put("playerUuid", playerUuid.toString());
        metadata.put("nick", nick);
        metadata.put("startedBy", startedBy);
        metadata.put("startedAt", startedAt);
        metadata.put("stoppedAt", stoppedAt);
        metadata.put("durationSeconds", durationSeconds);
        metadata.put("format", format);
        metadata.put("filePath", filePath);
        metadata.put("sha256", sha256);

        String svcVersion = ModList.get().getModContainerById("voicechat")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("Unknown");
        metadata.put("simpleVoiceChatVersion", svcVersion);
        metadata.put("minecraftVersion", SharedConstants.getCurrentVersion().getName());

        String modVersion = ModList.get().getModContainerById(VoiceControlMod.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("1.0.0");
        metadata.put("modVersion", modVersion);

        try (FileWriter writer = new FileWriter(jsonFile)) {
            GSON.toJson(metadata, writer);
        } catch (IOException e) {
            AdminLogger.error("SYSTEM", "Failed to write metadata JSON to " + jsonFile.getName() + ": " + e.getMessage());
        }
    }

    public static void writeWavHeader(OutputStream os, int dataSize) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(44);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // RIFF
        buffer.put("RIFF".getBytes());
        buffer.putInt(36 + dataSize);
        buffer.put("WAVE".getBytes());
        
        // fmt 
        buffer.put("fmt ".getBytes());
        buffer.putInt(16); // Subchunk1Size
        buffer.putShort((short) 1); // AudioFormat (1 = PCM)
        buffer.putShort((short) 1); // NumChannels (1 = Mono)
        buffer.putInt(48000); // SampleRate (48kHz)
        buffer.putInt(96000); // ByteRate (48000 * 2)
        buffer.putShort((short) 2); // BlockAlign (1 * 2)
        buffer.putShort((short) 16); // BitsPerSample
        
        // data
        buffer.put("data".getBytes());
        buffer.putInt(dataSize);
        
        os.write(buffer.array());
    }

    public static void fixWavHeader(File wavFile, int dataSize) {
        try (RandomAccessFile raf = new RandomAccessFile(wavFile, "rw")) {
            ByteBuffer buffer = ByteBuffer.allocate(44);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            buffer.put("RIFF".getBytes());
            buffer.putInt(36 + dataSize);
            buffer.put("WAVE".getBytes());
            
            buffer.put("fmt ".getBytes());
            buffer.putInt(16);
            buffer.putShort((short) 1);
            buffer.putShort((short) 1);
            buffer.putInt(48000);
            buffer.putInt(96000);
            buffer.putShort((short) 2);
            buffer.putShort((short) 16);
            
            buffer.put("data".getBytes());
            buffer.putInt(dataSize);
            
            raf.seek(0);
            raf.write(buffer.array());
        } catch (IOException e) {
            AdminLogger.error("SYSTEM", "Failed to fix WAV header for " + wavFile.getName() + ": " + e.getMessage());
        }
    }
}
