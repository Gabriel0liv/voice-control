package net.voicecontrol.audio;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.voicecontrol.Config;
import net.voicecontrol.VoiceControlMod;
import net.voicecontrol.logging.AdminLogger;
import net.voicecontrol.network.VoiceControlNetwork;
import net.voicecontrol.network.packets.AudioChunkPacket;
import net.voicecontrol.network.packets.AudioManifestPacket;
import net.voicecontrol.network.packets.AudioSyncCompletePacket;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class AudioImportManager {

    public static class AudioEntry {
        public final String id;
        public final File file;
        public final String sha256;
        public final long sizeBytes;
        public final String format;
        public final long lastModified;

        public AudioEntry(String id, File file, String sha256, long sizeBytes, String format, long lastModified) {
            this.id = id;
            this.file = file;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.format = format;
            this.lastModified = lastModified;
        }
    }

    private static final Map<String, AudioEntry> importedSounds = new ConcurrentHashMap<>();
    private static final Set<ServerPlayer> readyPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<UUID, Map<String, Boolean>> playerSyncStatus = new ConcurrentHashMap<>();

    public static void reloadAudios(MinecraftServer server, CommandSourceStack source) {
        String operator = source != null ? source.getTextName() : "SYSTEM";
        AdminLogger.info(operator, "Initiating custom audios reload asynchronously...");

        if (source != null) {
            source.sendSystemMessage(Component.literal("§e[VoiceControl] Iniciando importação e conversão de áudios em segundo plano..."));
        }

        CompletableFuture.runAsync(() -> {
            synchronized (AudioImportManager.class) {
                importedSounds.clear();
                playerSyncStatus.clear();

                Path baseFolder = VoiceControlMod.getBaseFolder();
                Path importPath = baseFolder.resolve(Config.SERVER.audioLibraryImportFolder.get());
                File importDir = importPath.toFile();

                if (!importDir.exists()) {
                    try {
                        Files.createDirectories(importPath);
                    } catch (IOException e) {
                        String errMsg = "Failed to create import directory: " + e.getMessage();
                        AdminLogger.error(operator, errMsg);
                        if (source != null) {
                            server.execute(() -> source.sendFailure(Component.literal("§c" + errMsg)));
                        }
                        return;
                    }
                }

                // Prepare transcoded cache directory
                Path transcodedPath = baseFolder.resolve("transcoded-cache");
                try {
                    Files.createDirectories(transcodedPath);
                } catch (IOException e) {
                    AdminLogger.error(operator, "Failed to create transcoded-cache directory: " + e.getMessage());
                }

                List<File> allFiles = new ArrayList<>();
                scanDirectory(importDir, allFiles);

                if (allFiles.isEmpty()) {
                    String msg = "No audio files found in " + importDir.getPath();
                    AdminLogger.info(operator, msg);
                    if (source != null) {
                        server.execute(() -> source.sendSystemMessage(Component.literal("§e" + msg)));
                    }
                    broadcastManifestToReadyPlayers();
                    return;
                }

                List<String> loadedSounds = new ArrayList<>();
                List<String> failedFiles = new ArrayList<>();

                for (File file : allFiles) {
                    String relativePath = importDir.toPath().relativize(file.toPath()).toString();
                    String nameLower = file.getName().toLowerCase();

                    // Check if extension is allowed
                    boolean extensionAllowed = false;
                    for (Object extObj : Config.SERVER.audioLibraryAllowedExtensions.get()) {
                        String ext = ((String) extObj).toLowerCase();
                        if (nameLower.endsWith("." + ext)) {
                            extensionAllowed = true;
                            break;
                        }
                    }

                    if (!extensionAllowed) continue;

                    String rawId = Sanitizer.sanitize(relativePath);
                    if (rawId == null || rawId.isEmpty()) {
                        AdminLogger.warn(operator, "Skipping file '" + relativePath + "': invalid path after sanitization.");
                        failedFiles.add(relativePath);
                        continue;
                    }

                    String soundId = "voicecontrol:" + rawId;
                    File finalFile = null;
                    boolean success = false;

                    if (nameLower.endsWith(".ogg")) {
                        finalFile = file;
                        success = true;
                    } else if (nameLower.endsWith(".mp3") || nameLower.endsWith(".wav")) {
                        if (Config.SERVER.audioLibraryAllowMp3WavTranscode.get()) {
                            // Determine transcoded target path
                            File targetOgg = transcodedPath.resolve(rawId + ".ogg").toFile();
                            File parentDir = targetOgg.getParentFile();
                            if (parentDir != null && !parentDir.exists()) {
                                parentDir.mkdirs();
                            }

                            // Check if already transcoded and source didn't modify since
                            if (targetOgg.exists() && targetOgg.lastModified() >= file.lastModified()) {
                                finalFile = targetOgg;
                                success = true;
                            } else {
                                success = OggConverter.convertToOgg(file, targetOgg);
                                if (success) {
                                    finalFile = targetOgg;
                                }
                            }
                        } else {
                            AdminLogger.warn(operator, "Conversion disabled. Skipping MP3/WAV file: " + relativePath);
                        }
                    }

                    if (success && finalFile != null && finalFile.exists()) {
                        // Enforce size limit
                        long sizeMb = finalFile.length() / (1024 * 1024);
                        if (sizeMb > Config.SERVER.audioLibraryMaxAudioFileSizeMb.get()) {
                            AdminLogger.error(operator, "File " + relativePath + " exceeds maximum allowed size (" + Config.SERVER.audioLibraryMaxAudioFileSizeMb.get() + "MB)");
                            failedFiles.add(relativePath);
                            continue;
                        }

                        String sha256 = calculateSHA256(finalFile);
                        if (!sha256.isEmpty()) {
                            AudioEntry entry = new AudioEntry(soundId, finalFile, sha256, finalFile.length(), "ogg", finalFile.lastModified());
                            importedSounds.put(soundId, entry);
                            loadedSounds.add(soundId);
                        } else {
                            failedFiles.add(relativePath);
                        }
                    } else {
                        failedFiles.add(relativePath);
                    }
                }

                // Copy variables for final notification
                final List<String> loaded = new ArrayList<>(loadedSounds);
                final List<String> failed = new ArrayList<>(failedFiles);

                // Print results to executor
                if (source != null) {
                    server.execute(() -> {
                        if (!loaded.isEmpty()) {
                            source.sendSystemMessage(Component.literal("§aLoaded " + loaded.size() + " custom sounds."));
                        }
                        if (!failed.isEmpty()) {
                            source.sendSystemMessage(Component.literal("§cFailed to load " + failed.size() + " files (see console/admin.log)."));
                        }
                    });
                }

                AdminLogger.info(operator, "Loaded sounds: " + loadedSounds + ". Failures: " + failedFiles);

                // Broadcast manifest to ready players
                if (Config.SERVER.audioLibrarySyncOnAudioReload.get()) {
                    broadcastManifestToReadyPlayers();
                }
            }
        });
    }

    private static void scanDirectory(File dir, List<File> filesList) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanDirectory(f, filesList);
            } else {
                filesList.add(f);
            }
        }
    }

    public static synchronized Map<String, AudioEntry> getImportedSounds() {
        return Collections.unmodifiableMap(new HashMap<>(importedSounds));
    }

    public static void registerReadyPlayer(ServerPlayer player) {
        readyPlayers.add(player);
        sendManifestToPlayer(player);
    }

    public static void handlePlayerLeave(ServerPlayer player) {
        readyPlayers.remove(player);
        playerSyncStatus.remove(player.getUUID());
    }

    private static void broadcastManifestToReadyPlayers() {
        List<AudioManifestPacket.ManifestEntry> entries = buildManifestEntries();
        AudioManifestPacket packet = new AudioManifestPacket(entries);
        for (ServerPlayer player : readyPlayers) {
            VoiceControlNetwork.sendToClient(packet, player);
        }
    }

    private static void sendManifestToPlayer(ServerPlayer player) {
        List<AudioManifestPacket.ManifestEntry> entries = buildManifestEntries();
        VoiceControlNetwork.sendToClient(new AudioManifestPacket(entries), player);
    }

    private static List<AudioManifestPacket.ManifestEntry> buildManifestEntries() {
        List<AudioManifestPacket.ManifestEntry> entries = new ArrayList<>();
        for (AudioEntry entry : importedSounds.values()) {
            entries.add(new AudioManifestPacket.ManifestEntry(
                    entry.id,
                    entry.file.getName(),
                    entry.sha256,
                    entry.sizeBytes,
                    -1.0,
                    entry.format,
                    entry.lastModified
            ));
        }
        return entries;
    }

    public static void streamFileToClient(ServerPlayer player, String soundId, String sha256) {
        CompletableFuture.runAsync(() -> {
            AudioEntry entry = importedSounds.get(soundId);
            if (entry == null || !entry.sha256.equalsIgnoreCase(sha256) || !entry.file.exists()) {
                VoiceControlNetwork.sendToClient(new AudioSyncCompletePacket(soundId, sha256, false), player);
                return;
            }

            int maxChunkSize = Config.SERVER.audioLibraryMaxChunkSizeBytes.get();
            byte[] buffer = new byte[maxChunkSize];
            try (FileInputStream fis = new FileInputStream(entry.file)) {
                long totalBytes = entry.file.length();
                int totalChunks = (int) Math.ceil((double) totalBytes / maxChunkSize);
                int chunkIndex = 0;
                int readBytes;

                while ((readBytes = fis.read(buffer)) > 0) {
                    byte[] chunkData = java.util.Arrays.copyOf(buffer, readBytes);
                    VoiceControlNetwork.sendToClient(new AudioChunkPacket(soundId, sha256, chunkIndex, totalChunks, chunkData), player);
                    chunkIndex++;
                    
                    // Tiny throttle sleep to prevent overwhelming network buffer
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException ignored) {}
                }

                VoiceControlNetwork.sendToClient(new AudioSyncCompletePacket(soundId, sha256, true), player);
            } catch (IOException e) {
                AdminLogger.error("SYSTEM", "Error streaming file " + soundId + " to client: " + e.getMessage());
                VoiceControlNetwork.sendToClient(new AudioSyncCompletePacket(soundId, sha256, false), player);
            }
        });
    }

    public static void updatePlayerSyncStatus(ServerPlayer player, String soundId, String sha256, boolean cached) {
        Map<String, Boolean> status = playerSyncStatus.computeIfAbsent(player.getUUID(), p -> new ConcurrentHashMap<>());
        status.put(soundId, cached);
    }

    public static boolean isPlayerReady(ServerPlayer player) {
        return readyPlayers.contains(player);
    }

    public static int sendManifestIfReady(ServerPlayer player) {
        if (isPlayerReady(player)) {
            sendManifestToPlayer(player);
            return 1;
        }
        return 0;
    }

    public static int sendManifestToReadyPlayers(Collection<ServerPlayer> players) {
        int count = 0;
        for (ServerPlayer player : players) {
            if (isPlayerReady(player)) {
                sendManifestToPlayer(player);
                count++;
            }
        }
        return count;
    }

    public static int broadcastManifestToReadyPlayersPublic() {
        int count = 0;
        List<AudioManifestPacket.ManifestEntry> entries = buildManifestEntries();
        AudioManifestPacket packet = new AudioManifestPacket(entries);
        for (ServerPlayer player : readyPlayers) {
            VoiceControlNetwork.sendToClient(packet, player);
            count++;
        }
        return count;
    }

    public static boolean isSoundCachedForPlayer(ServerPlayer player, String soundId) {
        Map<String, Boolean> status = playerSyncStatus.get(player.getUUID());
        return status != null && Boolean.TRUE.equals(status.get(soundId));
    }

    public static String calculateSHA256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = fis.read(buffer)) > 0) {
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
        } catch (Exception e) {
            AdminLogger.error("SYSTEM", "Failed to compute SHA-256: " + e.getMessage());
            return "";
        }
    }
}
