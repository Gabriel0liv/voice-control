package net.voicecontrol.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraft.sounds.SoundSource;
import net.voicecontrol.Config;
import net.voicecontrol.logging.AdminLogger;
import net.voicecontrol.network.VoiceControlNetwork;
import net.voicecontrol.network.packets.AudioRequestPacket;
import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.stb.STBVorbis;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class DynamicClientSoundEngine {
    private static final Gson GSON = new Gson();
    private static final ExecutorService DECODE_SERVICE = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "VoiceControl-Decoder");
        thread.setDaemon(true);
        return thread;
    });

    private static File cacheDir;
    private static final Map<String, String> serverManifest = new ConcurrentHashMap<>(); // soundId -> sha256
    private static final Map<String, String> cachedManifest = new ConcurrentHashMap<>(); // soundId -> sha256
    private static final Map<String, FileSyncTask> activeDownloads = new ConcurrentHashMap<>();

    // Keep track of active OpenAL sources
    private static final List<PlayingSound> playingSounds = Collections.synchronizedList(new ArrayList<>());
    // Main thread execution queue
    private static final Queue<Runnable> mainThreadQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static class FileSyncTask {
        public final String soundId;
        public final String sha256;
        private int totalChunks = -1;
        private byte[][] chunks;
        private final List<Runnable> runAfterSync = new ArrayList<>();

        public FileSyncTask(String soundId, String sha256) {
            this.soundId = soundId;
            this.sha256 = sha256;
        }

        public synchronized void initializeChunks(int totalChunks) {
            if (this.chunks == null) {
                this.totalChunks = totalChunks;
                this.chunks = new byte[totalChunks][];
            }
        }

        public synchronized void putChunk(int chunkIndex, int totalChunks, byte[] data) {
            initializeChunks(totalChunks);
            if (chunkIndex >= 0 && chunkIndex < this.totalChunks) {
                this.chunks[chunkIndex] = data;
            }
        }

        public synchronized byte[][] getChunks() {
            return chunks;
        }

        public synchronized boolean isComplete() {
            if (chunks == null) return false;
            for (byte[] chunk : chunks) {
                if (chunk == null) return false;
            }
            return true;
        }

        public synchronized void addCallback(Runnable runnable) {
            runAfterSync.add(runnable);
        }

        public synchronized void triggerCallbacks() {
            for (Runnable runnable : runAfterSync) {
                try {
                    runnable.run();
                } catch (Exception e) {
                    AdminLogger.error("CLIENT", "Error executing sync callback: " + e.getMessage());
                }
            }
        }
    }

    private static class PlayingSound {
        final String soundId;
        final String sourceCategory;
        final int alSourceId;
        final int alBufferId;
        final boolean positional;
        final double x, y, z;
        final float baseVolume;
        final float minVolume;

        PlayingSound(String soundId, String sourceCategory, int alSourceId, int alBufferId, boolean positional, double x, double y, double z, float baseVolume, float minVolume) {
            this.soundId = soundId;
            this.sourceCategory = sourceCategory;
            this.alSourceId = alSourceId;
            this.alBufferId = alBufferId;
            this.positional = positional;
            this.x = x;
            this.y = y;
            this.z = z;
            this.baseVolume = baseVolume;
            this.minVolume = minVolume;
        }
    }

    public static void init(File gameDir) {
        cacheDir = new File(gameDir, "voice-control/cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        loadManifest();
    }

    public static File getCachedFile(String sha256) {
        return new File(cacheDir, sha256 + ".ogg");
    }

    private static void loadManifest() {
        if (!Config.SERVER.dynamicSoundClientCacheEnabled.get()) {
            return;
        }
        File manifestFile = new File(cacheDir, "manifest.json");
        if (manifestFile.exists()) {
            try (FileReader reader = new FileReader(manifestFile)) {
                Map<String, String> loaded = GSON.fromJson(reader, new TypeToken<Map<String, String>>(){}.getType());
                if (loaded != null) {
                    cachedManifest.putAll(loaded);
                }
            } catch (Exception e) {
                AdminLogger.error("CLIENT", "Failed to load client manifest: " + e.getMessage());
            }
        }
    }

    public static void saveManifest() {
        if (!Config.SERVER.dynamicSoundClientCacheEnabled.get()) {
            return;
        }
        File manifestFile = new File(cacheDir, "manifest.json");
        try (FileWriter writer = new FileWriter(manifestFile)) {
            GSON.toJson(cachedManifest, writer);
        } catch (Exception e) {
            AdminLogger.error("CLIENT", "Failed to save client manifest: " + e.getMessage());
        }
    }

    public static Map<String, String> getClientManifest() {
        return cachedManifest;
    }

    private static String calculateSHA256(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            AdminLogger.error("CLIENT", "Failed to compute SHA-256 for bytes: " + e.getMessage());
            return "";
        }
    }

    private static String calculateSHA256(File file) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
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
            AdminLogger.error("CLIENT", "Failed to compute SHA-256 for file: " + e.getMessage());
            return "";
        }
    }

    public static void updateManifestFromServer(Map<String, String> newServerManifest) {
        serverManifest.clear();
        serverManifest.putAll(newServerManifest);

        cachedManifest.clear();

        if (!Config.SERVER.dynamicSoundClientCacheEnabled.get()) {
            return;
        }

        for (Map.Entry<String, String> entry : serverManifest.entrySet()) {
            String soundId = entry.getKey();
            String sha256 = entry.getValue();
            File cachedFile = getCachedFile(sha256);
            if (cachedFile.exists() && calculateSHA256(cachedFile).equalsIgnoreCase(sha256)) {
                cachedManifest.put(soundId, sha256);
            } else {
                requestSync(soundId, sha256);
            }
        }
        saveManifest();
    }

    public static void requestSync(String soundId, String sha256) {
        if (!Config.SERVER.dynamicSoundClientCacheEnabled.get()) {
            return;
        }
        if (activeDownloads.containsKey(sha256)) return;
        activeDownloads.put(sha256, new FileSyncTask(soundId, sha256));
        VoiceControlNetwork.sendToServer(new AudioRequestPacket(soundId, sha256));
    }

    public static void handleChunk(String soundId, String sha256, int chunkIndex, int totalChunks, byte[] data) {
        if (!Config.SERVER.dynamicSoundClientCacheEnabled.get()) {
            return;
        }
        FileSyncTask task = activeDownloads.computeIfAbsent(sha256, s -> new FileSyncTask(soundId, sha256));
        task.putChunk(chunkIndex, totalChunks, data);
    }

    public static void handleSyncComplete(String soundId, String sha256, boolean success) {
        FileSyncTask task = activeDownloads.remove(sha256);
        if (task == null) {
            AdminLogger.error("CLIENT", "No sync task found for sound: " + soundId);
            return;
        }

        if (!success || !task.isComplete()) {
            AdminLogger.error("CLIENT", "Failed to sync sound file " + soundId + " (success=" + success + ", complete=" + (task.isComplete()) + ")");
            VoiceControlNetwork.sendToServer(new net.voicecontrol.network.packets.AudioClientSyncStatusPacket(soundId, sha256, false));
            return;
        }

        // Assemble chunks
        File cachedFile = getCachedFile(sha256);
        try {
            byte[][] chunks = task.getChunks();
            int totalSize = 0;
            for (byte[] chunk : chunks) {
                if (chunk != null) totalSize += chunk.length;
            }
            byte[] completeFile = new byte[totalSize];
            int offset = 0;
            for (byte[] chunk : chunks) {
                if (chunk != null) {
                    System.arraycopy(chunk, 0, completeFile, offset, chunk.length);
                    offset += chunk.length;
                }
            }

            // Local SHA-256 validation
            String calculatedSha = calculateSHA256(completeFile);
            if (!calculatedSha.equalsIgnoreCase(sha256)) {
                AdminLogger.error("CLIENT", "SHA-256 verification failed for " + soundId + ". Expected: " + sha256 + ", Got: " + calculatedSha);
                if (cachedFile.exists()) {
                    cachedFile.delete();
                }
                VoiceControlNetwork.sendToServer(new net.voicecontrol.network.packets.AudioClientSyncStatusPacket(soundId, sha256, false));
                return;
            }

            java.nio.file.Files.write(cachedFile.toPath(), completeFile);
            cachedManifest.put(soundId, sha256);
            saveManifest();
            AdminLogger.info("CLIENT", "Successfully synchronized and cached sound: " + soundId);
            
            // Trigger playback if requested during sync
            task.triggerCallbacks();
        } catch (IOException e) {
            AdminLogger.error("CLIENT", "Failed to save synchronized sound " + soundId + ": " + e.getMessage());
            VoiceControlNetwork.sendToServer(new net.voicecontrol.network.packets.AudioClientSyncStatusPacket(soundId, sha256, false));
        }
    }

    public static void playSound(String soundId, String sourceCategory, boolean positional, double x, double y, double z, float volume, float pitch, float minVolume, float attenuation) {
        if (!Config.SERVER.dynamicSoundEnabled.get()) return;

        String sha256 = serverManifest.get(soundId);
        if (sha256 == null) {
            sha256 = cachedManifest.get(soundId);
        }
        if (sha256 == null) {
            AdminLogger.warn("CLIENT", "Sound not in manifest: " + soundId);
            return;
        }

        File cachedFile = getCachedFile(sha256);
        boolean fileExists = cachedFile.exists();

        boolean isCached = false;
        if (!Config.SERVER.dynamicSoundClientCacheEnabled.get()) {
            if (fileExists) {
                isCached = true;
            } else {
                AdminLogger.warn("CLIENT", "Cache is disabled and file does not exist: " + soundId);
                return;
            }
        } else {
            isCached = cachedManifest.containsKey(soundId) && fileExists;
        }

        if (!isCached) {
            if (Config.SERVER.dynamicSoundPlayAfterDownloadIfMissing.get()) {
                FileSyncTask download = activeDownloads.get(sha256);
                if (download != null) {
                    download.addCallback(() -> playSound(soundId, sourceCategory, positional, x, y, z, volume, pitch, minVolume, attenuation));
                } else {
                    requestSync(soundId, sha256);
                    FileSyncTask newTask = activeDownloads.get(sha256);
                    if (newTask != null) {
                        newTask.addCallback(() -> playSound(soundId, sourceCategory, positional, x, y, z, volume, pitch, minVolume, attenuation));
                    }
                }
            } else {
                requestSync(soundId, sha256);
            }
            return;
        }

        // Decode OGG asynchronously to not block client render thread
        DECODE_SERVICE.submit(() -> {
            try {
                PCMData pcm = decodeOgg(cachedFile);
                mainThreadQueue.offer(() -> {
                    try {
                        triggerOpenALPlay(soundId, sourceCategory, pcm, positional, x, y, z, volume, pitch, minVolume, attenuation);
                    } catch (Exception e) {
                        AdminLogger.error("CLIENT", "Failed to play sound via OpenAL " + soundId + ": " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                AdminLogger.error("CLIENT", "Failed to decode OGG file " + soundId + ": " + e.getMessage());
            }
        });
    }

    private static void triggerOpenALPlay(String soundId, String sourceCategory, PCMData pcm, boolean positional, double x, double y, double z, float volume, float pitch, float minVolume, float attenuation) {
        // Ensure concurrent sound limit is respected
        int maxConcurrent = Config.SERVER.dynamicSoundMaxConcurrentSounds.get();
        synchronized (playingSounds) {
            while (playingSounds.size() >= maxConcurrent && !playingSounds.isEmpty()) {
                PlayingSound oldest = playingSounds.remove(0);
                try {
                    AL10.alSourceStop(oldest.alSourceId);
                    AL10.alDeleteSources(oldest.alSourceId);
                    AL10.alDeleteBuffers(oldest.alBufferId);
                } catch (Exception ignored) {}
            }
        }

        int bufferId = AL10.alGenBuffers();
        int format = (pcm.channels == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        
        AL10.alBufferData(bufferId, format, pcm.data, pcm.sampleRate);
        MemoryUtil.memFree(pcm.data); // Free the STB allocated memory immediately

        int sourceId = AL10.alGenSources();
        AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId);
        AL10.alSourcef(sourceId, AL10.AL_PITCH, pitch);

        // Apply spatial properties
        if (positional) {
            AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(sourceId, AL10.AL_POSITION, (float) x, (float) y, (float) z);
            AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, attenuation);
            AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, 8.0f); // Standard Reference Distance
            AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, 128.0f);
        } else {
            AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(sourceId, AL10.AL_POSITION, 0.0f, 0.0f, 0.0f);
        }

        // Apply combined volumes
        float categoryVol = getCategoryVolume(sourceCategory);
        float masterVol = getCategoryVolume("master");
        float finalVolume = volume * categoryVol * masterVol;
        if (finalVolume < minVolume) {
            finalVolume = minVolume;
        }
        AL10.alSourcef(sourceId, AL10.AL_GAIN, finalVolume);

        AL10.alSourcePlay(sourceId);

        PlayingSound sound = new PlayingSound(soundId, sourceCategory, sourceId, bufferId, positional, x, y, z, volume, minVolume);
        playingSounds.add(sound);
    }

    public static void stopSounds(String soundId, String category) {
        synchronized (playingSounds) {
            Iterator<PlayingSound> it = playingSounds.iterator();
            while (it.hasNext()) {
                PlayingSound sound = it.next();
                boolean matchSound = (soundId == null || sound.soundId.equalsIgnoreCase(soundId));
                boolean matchCategory = (category == null || sound.sourceCategory.equalsIgnoreCase(category));
                
                if (matchSound && matchCategory) {
                    AL10.alSourceStop(sound.alSourceId);
                    AL10.alDeleteSources(sound.alSourceId);
                    AL10.alDeleteBuffers(sound.alBufferId);
                    it.remove();
                }
            }
        }
    }

    public static void updateVolumeSettings() {
        synchronized (playingSounds) {
            float masterVol = getCategoryVolume("master");
            for (PlayingSound sound : playingSounds) {
                float categoryVol = getCategoryVolume(sound.sourceCategory);
                float finalVolume = sound.baseVolume * categoryVol * masterVol;
                if (finalVolume < sound.minVolume) {
                    finalVolume = sound.minVolume;
                }
                AL10.alSourcef(sound.alSourceId, AL10.AL_GAIN, finalVolume);
            }
        }
    }

    public static void clientTick() {
        // Run queued tasks on client main thread
        Runnable task;
        while ((task = mainThreadQueue.poll()) != null) {
            task.run();
        }

        // Update listener position & orientation relative to camera
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.gameRenderer != null) {
            net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
            if (camera.isInitialized()) {
                Vec3 pos = camera.getPosition();
                org.joml.Vector3f look = camera.getLookVector();
                org.joml.Vector3f up = camera.getUpVector();
                AL10.alListener3f(AL10.AL_POSITION, (float) pos.x, (float) pos.y, (float) pos.z);
                float[] orientation = new float[]{look.x, look.y, look.z, up.x, up.y, up.z};
                AL10.alListenerfv(AL10.AL_ORIENTATION, orientation);
            }
        }

        // Clean up playing sounds that have finished
        synchronized (playingSounds) {
            Iterator<PlayingSound> it = playingSounds.iterator();
            while (it.hasNext()) {
                PlayingSound sound = it.next();
                int state = AL10.alGetSourcei(sound.alSourceId, AL10.AL_SOURCE_STATE);
                if (state == AL10.AL_STOPPED) {
                    AL10.alDeleteSources(sound.alSourceId);
                    AL10.alDeleteBuffers(sound.alBufferId);
                    it.remove();
                }
            }
        }
    }

    public static void cleanup() {
        mainThreadQueue.clear();
        synchronized (playingSounds) {
            for (PlayingSound sound : playingSounds) {
                AL10.alSourceStop(sound.alSourceId);
                AL10.alDeleteSources(sound.alSourceId);
                AL10.alDeleteBuffers(sound.alBufferId);
            }
            playingSounds.clear();
        }
    }

    private static float getCategoryVolume(String categoryName) {
        try {
            SoundSource source = SoundSource.valueOf(categoryName.toUpperCase());
            return Minecraft.getInstance().options.getSoundSourceVolume(source);
        } catch (IllegalArgumentException e) {
            return 1.0f;
        }
    }

    private static PCMData decodeOgg(File file) throws IOException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channelsBuffer = stack.mallocInt(1);
            IntBuffer sampleRateBuffer = stack.mallocInt(1);
            ShortBuffer pcm = STBVorbis.stb_vorbis_decode_filename(file.getAbsolutePath(), channelsBuffer, sampleRateBuffer);
            if (pcm == null) {
                throw new IOException("Failed to decode OGG file: " + file.getName());
            }
            int channels = channelsBuffer.get(0);
            int sampleRate = sampleRateBuffer.get(0);
            return new PCMData(pcm, channels, sampleRate);
        }
    }

    private static class PCMData {
        final ShortBuffer data;
        final int channels;
        final int sampleRate;

        PCMData(ShortBuffer data, int channels, int sampleRate) {
            this.data = data;
            this.channels = channels;
            this.sampleRate = sampleRate;
        }
    }
}
