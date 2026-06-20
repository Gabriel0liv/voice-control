package net.voicecontrol.audio;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.voicecontrol.Config;
import net.voicecontrol.VoiceControlMod;
import net.voicecontrol.logging.AdminLogger;
import net.voicecontrol.pack.ResourcePackBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

public class AudioImportManager {
    private static final Map<String, File> importedSounds = new HashMap<>();

    public static void reloadAudios(MinecraftServer server, CommandSourceStack source) {
        String operator = source != null ? source.getTextName() : "SYSTEM";
        AdminLogger.info(operator, "Initiating custom audios reload asynchronously...");

        if (source != null) {
            source.sendSystemMessage(Component.literal("§e[VoiceControl] Iniciando importação e conversão de áudios em segundo plano..."));
        }

        CompletableFuture.runAsync(() -> {
            synchronized (AudioImportManager.class) {
                importedSounds.clear();

                Path inputPath = VoiceControlMod.getBaseFolder().resolve("imported-audios");
                File inputDir = inputPath.toFile();
                if (!inputDir.exists()) {
                    try {
                        Files.createDirectories(inputPath);
                    } catch (IOException e) {
                        String errMsg = "Failed to create imported-audios directory: " + e.getMessage();
                        AdminLogger.error(operator, errMsg);
                        if (source != null) {
                            server.execute(() -> source.sendFailure(Component.literal("§c" + errMsg)));
                        }
                        return;
                    }
                }

                File[] files = inputDir.listFiles();
                if (files == null || files.length == 0) {
                    String msg = "No custom audio files found in " + inputDir.getPath();
                    AdminLogger.info(operator, msg);
                    if (source != null) {
                        server.execute(() -> source.sendSystemMessage(Component.literal("§e" + msg)));
                    }
                    rebuildPackIfEnabled(server, source, operator);
                    return;
                }

                String rawNamespace = Config.SERVER.audioImportNamespace.get();
                String namespace = rawNamespace == null ? "voicecontrol" : rawNamespace.toLowerCase().replaceAll("[^a-z0-9_\\-.]", "");
                if (namespace.isEmpty()) {
                    namespace = "voicecontrol";
                }

                Path soundAssetsPath = VoiceControlMod.getBaseFolder().resolve("resourcepack/assets/" + namespace + "/sounds");
                
                try {
                    // Clean previous sounds working directory
                    if (Files.exists(soundAssetsPath)) {
                        try (java.util.stream.Stream<Path> paths = Files.walk(soundAssetsPath)) {
                            paths.map(Path::toFile)
                                 .sorted(Comparator.reverseOrder())
                                 .forEach(File::delete);
                        }
                    }
                    Files.createDirectories(soundAssetsPath);
                } catch (IOException e) {
                    String errMsg = "Failed to prepare resourcepack working directories: " + e.getMessage();
                    AdminLogger.error(operator, errMsg);
                    if (source != null) {
                        server.execute(() -> source.sendFailure(Component.literal("§c" + errMsg)));
                    }
                    return;
                }

                List<String> failedFiles = new ArrayList<>();
                List<String> loadedSounds = new ArrayList<>();

                for (File file : files) {
                    if (file.isDirectory()) continue;
                    
                    String fileName = file.getName();
                    String nameLower = fileName.toLowerCase();
                    if (!nameLower.endsWith(".ogg") && !nameLower.endsWith(".mp3") && !nameLower.endsWith(".wav")) {
                        continue; // Skip unsupported formats
                    }

                    // Sanitize name to sound ID
                    String soundId = Sanitizer.sanitize(fileName);
                    if (soundId == null || soundId.isEmpty()) {
                        AdminLogger.error(operator, "Skipping file '" + fileName + "': name was empty or invalid after sanitization.");
                        failedFiles.add(fileName);
                        continue;
                    }
                    
                    // Resolve duplicates
                    String uniqueSoundId = soundId;
                    int suffix = 1;
                    while (importedSounds.containsKey(uniqueSoundId)) {
                        uniqueSoundId = soundId + "_" + suffix;
                        suffix++;
                    }
                    soundId = uniqueSoundId;

                    File targetOggFile = soundAssetsPath.resolve(soundId + ".ogg").toFile();

                    boolean success = false;
                    if (nameLower.endsWith(".ogg")) {
                        try {
                            Files.copy(file.toPath(), targetOggFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            success = true;
                        } catch (IOException e) {
                            AdminLogger.error(operator, "Failed to copy OGG file " + fileName + ": " + e.getMessage());
                        }
                    } else if (nameLower.endsWith(".mp3") || nameLower.endsWith(".wav")) {
                        if (Config.SERVER.audioImportConvertToOgg.get()) {
                            success = OggConverter.convertToOgg(file, targetOggFile);
                        } else {
                            AdminLogger.warn(operator, "Conversion disabled. Skipping MP3/WAV file: " + fileName);
                        }
                    }

                    if (success) {
                        importedSounds.put(soundId, targetOggFile);
                        loadedSounds.add(namespace + ":" + soundId);
                    } else {
                        failedFiles.add(fileName);
                    }
                }

                // Copy variables for final notification
                final List<String> loaded = new ArrayList<>(loadedSounds);
                final List<String> failed = new ArrayList<>(failedFiles);

                // Print results to chat safely on server main thread
                if (source != null) {
                    server.execute(() -> {
                        if (!loaded.isEmpty()) {
                            source.sendSystemMessage(Component.literal("§aLoaded " + loaded.size() + " custom sounds:"));
                            for (String sound : loaded) {
                                source.sendSystemMessage(Component.literal("§a - " + sound));
                            }
                        }
                        if (!failed.isEmpty()) {
                            source.sendSystemMessage(Component.literal("§cFailed to load " + failed.size() + " files (see console/admin.log):"));
                            for (String f : failed) {
                                source.sendSystemMessage(Component.literal("§c - " + f));
                            }
                        }
                    });
                }

                AdminLogger.info(operator, "Loaded sounds: " + loadedSounds + ". Failures: " + failedFiles);

                // Build the resource pack
                rebuildPackIfEnabled(server, source, operator);
            }
        });
    }

    private static void rebuildPackIfEnabled(MinecraftServer server, CommandSourceStack source, String operator) {
        if (Config.SERVER.resourcePackEnabled.get() && Config.SERVER.resourcePackAutoBuildOnReload.get()) {
            ResourcePackBuilder.buildPack(server, source);
        }
    }

    public static synchronized Map<String, File> getImportedSounds() {
        return Collections.unmodifiableMap(new HashMap<>(importedSounds));
    }
}
