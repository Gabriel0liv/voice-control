package net.voicecontrol.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.voicecontrol.Config;
import net.voicecontrol.VoiceControlMod;
import net.voicecontrol.audio.AudioImportManager;
import net.voicecontrol.logging.AdminLogger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ResourcePackBuilder {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static String packSha1 = "";
    private static File lastBuiltPack = null;

    public static synchronized void buildPack(MinecraftServer server, CommandSourceStack source) {
        String operator = source != null ? source.getTextName() : "SYSTEM";
        AdminLogger.info(operator, "Building resource pack...");

        Path rpDir = VoiceControlMod.getBaseFolder().resolve("resourcepack");
        String namespace = Config.SERVER.audioImportNamespace.get().toLowerCase();

        // 1. Write pack.mcmeta
        File mcmetaFile = rpDir.resolve("pack.mcmeta").toFile();
        Map<String, Object> packMeta = new HashMap<>();
        Map<String, Object> packData = new HashMap<>();
        packData.put("pack_format", 15); // Minecraft 1.20.1 pack format
        packData.put("description", "VoiceControl mod custom sounds resource pack");
        packMeta.put("pack", packData);

        try (FileWriter fw = new FileWriter(mcmetaFile)) {
            GSON.toJson(packMeta, fw);
        } catch (IOException e) {
            String msg = "Failed to write pack.mcmeta: " + e.getMessage();
            AdminLogger.error(operator, msg);
            if (source != null) {
                server.execute(() -> source.sendFailure(Component.literal("§c" + msg)));
            }
            return;
        }

        // 2. Write sounds.json
        File soundsJsonFile = rpDir.resolve("assets/" + namespace + "/sounds.json").toFile();
        // Ensure assets/<namespace> exists
        soundsJsonFile.getParentFile().mkdirs();

        Map<String, Map<String, Object>> soundsJson = new HashMap<>();
        for (String soundId : AudioImportManager.getImportedSounds().keySet()) {
            Map<String, Object> soundEntry = new HashMap<>();
            // The sound identifier in list defaults to mapping "soundId" -> assets/namespace/sounds/soundId.ogg
            soundEntry.put("sounds", Collections.singletonList(soundId));
            soundsJson.put(soundId, soundEntry);
        }

        try (FileWriter fw = new FileWriter(soundsJsonFile)) {
            GSON.toJson(soundsJson, fw);
        } catch (IOException e) {
            String msg = "Failed to write sounds.json: " + e.getMessage();
            AdminLogger.error(operator, msg);
            if (source != null) {
                server.execute(() -> source.sendFailure(Component.literal("§c" + msg)));
            }
            return;
        }

        // 3. Zip files
        File outputZip = rpDir.resolve("build/voicecontrol-pack.zip").toFile();
        outputZip.getParentFile().mkdirs();

        try {
            zipDirectory(rpDir.toFile(), outputZip);
        } catch (IOException e) {
            String msg = "Failed to zip resource pack: " + e.getMessage();
            AdminLogger.error(operator, msg);
            if (source != null) {
                server.execute(() -> source.sendFailure(Component.literal("§c" + msg)));
            }
            return;
        }

        // 4. Calculate SHA-1
        packSha1 = calculateSHA1(outputZip);
        lastBuiltPack = outputZip;

        String successMsg = "Resource pack built successfully! File: " + outputZip.getName() + " | SHA-1: " + packSha1;
        AdminLogger.info(operator, successMsg);
        if (source != null) {
            server.execute(() -> source.sendSystemMessage(Component.literal("§a" + successMsg)));
        }
    }

    private static void zipDirectory(File sourceDir, File outZipFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outZipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            
            // Only zip pack.mcmeta and assets/ directory
            File mcmeta = new File(sourceDir, "pack.mcmeta");
            if (mcmeta.exists()) {
                addToZip(mcmeta, sourceDir, zos);
            }
            
            File assets = new File(sourceDir, "assets");
            if (assets.exists()) {
                zipDirHelper(assets, sourceDir, zos);
            }
        }
    }

    private static void zipDirHelper(File dir, File baseDir, ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                zipDirHelper(f, baseDir, zos);
            } else {
                addToZip(f, baseDir, zos);
            }
        }
    }

    private static void addToZip(File file, File baseDir, ZipOutputStream zos) throws IOException {
        String relativePath = baseDir.toURI().relativize(file.toURI()).getPath();
        relativePath = relativePath.replace('\\', '/');
        ZipEntry entry = new ZipEntry(relativePath);
        zos.putNextEntry(entry);
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                zos.write(buffer, 0, len);
            }
        }
        zos.closeEntry();
    }

    public static String calculateSHA1(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
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
            AdminLogger.error("SYSTEM", "Failed to calculate SHA-1 for file: " + e.getMessage());
            return "";
        }
    }

    public static String getPackSha1() {
        return packSha1;
    }

    public static File getLastBuiltPack() {
        return lastBuiltPack;
    }
}
