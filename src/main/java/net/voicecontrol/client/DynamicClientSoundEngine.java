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
    private static final Map<String, Long> RECENT_CLIENT_PLAYS = new ConcurrentHashMap<>();

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
        boolean stopRequested = false;
        boolean pendingCleanup = false;
        int cleanupTicks = 0;

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

    private static void clearOpenALErrors(String context) {
        try {
            int error;
            while ((error = AL10.alGetError()) != AL10.AL_NO_ERROR) {
                ClientAudioDebugLogger.warn("Cleared OpenAL error before " + context + ": 0x" + Integer.toHexString(error));
            }
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed to clear OpenAL errors for " + context + ": " + t.getMessage());
        }
    }

    private static boolean checkOpenALError(String context) {
        try {
            int error = AL10.alGetError();
            if (error != AL10.AL_NO_ERROR) {
                ClientAudioDebugLogger.error("OpenAL error detected in " + context + ": 0x" + Integer.toHexString(error));
                return true;
            }
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed to check OpenAL error for " + context + ": " + t.getMessage());
        }
        return false;
    }

    private static boolean safeIsSource(int source, String context) {
        try {
            boolean result = AL10.alIsSource(source);
            checkOpenALError("alIsSource " + context);
            return result;
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed to check if source: " + source + " (" + context + "): " + t.getMessage());
            return false;
        }
    }

    private static boolean safeIsBuffer(int buffer, String context) {
        try {
            boolean result = AL10.alIsBuffer(buffer);
            checkOpenALError("alIsBuffer " + context);
            return result;
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed to check if buffer: " + buffer + " (" + context + "): " + t.getMessage());
            return false;
        }
    }

    private static void safeSourceStop(int source, String context) {
        try {
            if (safeIsSource(source, "safeSourceStop check")) {
                AL10.alSourceStop(source);
                checkOpenALError("alSourceStop " + context);
            }
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed to stop source: " + source + " (" + context + "): " + t.getMessage());
        }
    }

    private static void safeSourcei(int source, int param, int value, String context) {
        try {
            if (safeIsSource(source, "safeSourcei check")) {
                AL10.alSourcei(source, param, value);
                checkOpenALError("alSourcei " + context);
            }
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed sourcei on source: " + source + ", param: " + param + ", value: " + value + " (" + context + "): " + t.getMessage());
        }
    }

    private static void safeSourcef(int source, int param, float value, String context) {
        try {
            if (safeIsSource(source, "safeSourcef check")) {
                AL10.alSourcef(source, param, value);
                checkOpenALError("alSourcef " + context);
            }
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed sourcef on source: " + source + ", param: " + param + ", value: " + value + " (" + context + "): " + t.getMessage());
        }
    }

    private static int safeGetSourceState(int source, String context) {
        try {
            if (safeIsSource(source, "safeGetSourceState check")) {
                int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                checkOpenALError("alGetSourcei " + context);
                return state;
            }
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed to get source state for: " + source + " (" + context + "): " + t.getMessage());
        }
        return AL10.AL_STOPPED;
    }

    private static void safeDeleteSource(int source, String context) {
        try {
            if (safeIsSource(source, "safeDeleteSource check")) {
                AL10.alDeleteSources(source);
                checkOpenALError("alDeleteSources " + context);
            }
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed to delete source: " + source + " (" + context + "): " + t.getMessage());
        }
    }

    private static void safeDeleteBuffer(int buffer, String context) {
        try {
            if (safeIsBuffer(buffer, "safeDeleteBuffer check")) {
                AL10.alDeleteBuffers(buffer);
                checkOpenALError("alDeleteBuffers " + context);
            }
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed to delete buffer: " + buffer + " (" + context + "): " + t.getMessage());
        }
    }

    private static int safeGenSource(String context) {
        try {
            int source = AL10.alGenSources();
            checkOpenALError("alGenSources " + context);
            return source;
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed to generate source (" + context + "): " + t.getMessage());
            return 0;
        }
    }

    private static int safeGenBuffer(String context) {
        try {
            int buffer = AL10.alGenBuffers();
            checkOpenALError("alGenBuffers " + context);
            return buffer;
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed to generate buffer (" + context + "): " + t.getMessage());
            return 0;
        }
    }

    private static float calculateEffectiveVolume(PlayingSound sound) {
        float masterVol = getCategoryVolume("master");
        float categoryVol = getCategoryVolume(sound.sourceCategory);

        if (sound.baseVolume <= 0.0f || masterVol <= 0.0f || categoryVol <= 0.0f) {
            return 0.0f;
        }

        float finalVolume = sound.baseVolume * masterVol * categoryVol;

        if (sound.minVolume > 0.0f && finalVolume < sound.minVolume) {
            finalVolume = sound.minVolume;
        }

        return finalVolume;
    }

    private static void executeDelayedCleanup(PlayingSound sound) {
        try {
            ClientAudioDebugLogger.info("Executing delayed OpenAL cleanup for " + sound.soundId + " (source: " + sound.alSourceId + ", buffer: " + sound.alBufferId + ")");

            if (Config.SERVER.dynamicSoundDebugDisableOpenALCleanup.get()) {
                ClientAudioDebugLogger.warnOnceEvery(
                    "skip-cleanup-" + sound.soundId,
                    "OpenAL cleanup skipped by debug config: sound=" + sound.soundId,
                    5000
                );
                return;
            }

            clearOpenALErrors("cleanup " + sound.soundId);

            if (safeIsSource(sound.alSourceId, "delayedCleanup source")) {
                safeSourceStop(sound.alSourceId, "delayedCleanup stop");
                safeSourcei(sound.alSourceId, AL10.AL_BUFFER, 0, "delayedCleanup detach");
                safeDeleteSource(sound.alSourceId, "delayedCleanup delete");
            }

            if (safeIsBuffer(sound.alBufferId, "delayedCleanup buffer")) {
                safeDeleteBuffer(sound.alBufferId, "delayedCleanup delete");
            }

            checkOpenALError("cleanup " + sound.soundId);
            ClientAudioDebugLogger.info("[VoiceControl] OpenAL cleanup completed: sound=" + sound.soundId);
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("OpenAL cleanup crashed safely for " + sound.soundId + ": " + t.getMessage());
        }
    }

    private static void requestStopAndDelayedCleanup(PlayingSound sound) {
        try {
            ClientAudioDebugLogger.info("Stop requested: sound=" + sound.soundId + ", source=" + sound.alSourceId + ", buffer=" + sound.alBufferId);
            // Apply gain 0
            safeSourcef(sound.alSourceId, AL10.AL_GAIN, 0.0f, "requestStopAndDelayedCleanup gain 0");
            // Call stop with safe wrapper
            safeSourceStop(sound.alSourceId, "requestStopAndDelayedCleanup stop");
            
            sound.stopRequested = true;
            sound.pendingCleanup = true;
            sound.cleanupTicks = 0;
            int ticks = Config.SERVER.dynamicSoundCleanupDelayTicks.get();
            ClientAudioDebugLogger.info("Delayed cleanup scheduled: sound=" + sound.soundId + ", delay=" + ticks);
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed to request stop for " + sound.soundId + ": " + t.getMessage());
        }
    }

    public static void init(File gameDir) {
        ClientAudioDebugLogger.init(gameDir);
        ClientAudioDebugLogger.info("Initializing client sound engine cache directory...");
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

    public static boolean handleSyncComplete(String soundId, String sha256, boolean success) {
        if (!Config.SERVER.dynamicSoundClientCacheEnabled.get()) {
            AdminLogger.warn("CLIENT", "Ignoring sync complete because dynamic sound client cache is disabled.");
            return false;
        }

        FileSyncTask task = activeDownloads.remove(sha256);
        if (task == null) {
            AdminLogger.error("CLIENT", "No sync task found for sound: " + soundId);
            return false;
        }

        if (!success || !task.isComplete()) {
            AdminLogger.error("CLIENT", "Failed to sync sound file " + soundId + " (success=" + success + ", complete=" + (task.isComplete()) + ")");
            return false;
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
                return false;
            }

            java.nio.file.Files.write(cachedFile.toPath(), completeFile);
            cachedManifest.put(soundId, sha256);
            saveManifest();
            AdminLogger.info("CLIENT", "Successfully synchronized and cached sound: " + soundId);
            
            // Trigger playback if requested during sync
            task.triggerCallbacks();
            return true;
        } catch (IOException e) {
            AdminLogger.error("CLIENT", "Failed to save synchronized sound " + soundId + ": " + e.getMessage());
            return false;
        }
    }

    public static void playSound(String soundId, String sourceCategory, boolean positional, double x, double y, double z, float volume, float pitch, float minVolume, float attenuation) {
        if (!Config.SERVER.dynamicSoundEnabled.get()) return;

        if (!Config.SERVER.dynamicSoundUseOpenALPlayback.get()) {
            ClientAudioDebugLogger.warnOnceEvery("playback-disabled", "Dynamic OpenAL playback is disabled by config.", 10000);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§c[VoiceControl] Dynamic OpenAL playback is disabled by config."), true);
            }
            return;
        }

        // Client-side deduplication check
        int dedupeWindow = Config.SERVER.dynamicSoundDedupeWindowTicks.get();
        if (dedupeWindow > 0) {
            long now = (Minecraft.getInstance().level != null) ? Minecraft.getInstance().level.getGameTime() : (System.currentTimeMillis() / 50L);
            String key = soundId + "_" + sourceCategory + "_" + positional + "_" + x + "_" + y + "_" + z;
            Long lastPlay = RECENT_CLIENT_PLAYS.get(key);
            if (lastPlay != null && (now - lastPlay) < dedupeWindow) {
                ClientAudioDebugLogger.warnOnceEvery("dup-client-" + key, "[VoiceControl] Duplicate playsound ignored client-side: sound=" + soundId + ", category=" + sourceCategory, 5000);
                return;
            }
            RECENT_CLIENT_PLAYS.put(key, now);
        }

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
        // Stop existing identical sound if configured
        if (Config.SERVER.dynamicSoundStopExistingIdenticalBeforePlay.get()) {
            synchronized (playingSounds) {
                Iterator<PlayingSound> it = playingSounds.iterator();
                while (it.hasNext()) {
                    PlayingSound existing = it.next();
                    boolean matches = existing.soundId.equalsIgnoreCase(soundId)
                            && existing.sourceCategory.equalsIgnoreCase(sourceCategory)
                            && existing.positional == positional;
                    if (matches && positional) {
                        matches = (existing.x == x && existing.y == y && existing.z == z);
                    }
                    if (matches) {
                        ClientAudioDebugLogger.info("[VoiceControl] Stopped existing identical sound before replay: sound=" + soundId);
                        executeDelayedCleanup(existing);
                        it.remove();
                    }
                }
            }
        }

        // Ensure concurrent sound limit is respected
        int maxConcurrent = Config.SERVER.dynamicSoundMaxConcurrentSounds.get();
        synchronized (playingSounds) {
            while (playingSounds.size() >= maxConcurrent && !playingSounds.isEmpty()) {
                PlayingSound oldest = playingSounds.remove(0);
                executeDelayedCleanup(oldest);
            }
        }

        int bufferId = safeGenBuffer("triggerOpenALPlay");
        int format = (pcm.channels == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        
        try {
            AL10.alBufferData(bufferId, format, pcm.data, pcm.sampleRate);
            checkOpenALError("alBufferData");
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed alBufferData: " + t.getMessage());
        }
        MemoryUtil.memFree(pcm.data); // Free the STB allocated memory immediately

        int sourceId = safeGenSource("triggerOpenALPlay");
        safeSourcei(sourceId, AL10.AL_BUFFER, bufferId, "triggerOpenALPlay bind");
        safeSourcef(sourceId, AL10.AL_PITCH, pitch, "triggerOpenALPlay pitch");

        // Apply spatial properties
        if (positional) {
            safeSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE, "spatial relative");
            try {
                AL10.alSource3f(sourceId, AL10.AL_POSITION, (float) x, (float) y, (float) z);
                checkOpenALError("alSource3f AL_POSITION");
            } catch (Throwable t) {
                ClientAudioDebugLogger.error("Failed alSource3f AL_POSITION: " + t.getMessage());
            }
            safeSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, attenuation, "spatial rolloff");
            safeSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, 8.0f, "spatial refdist"); // Standard Reference Distance
            safeSourcef(sourceId, AL10.AL_MAX_DISTANCE, 128.0f, "spatial maxdist");
        } else {
            safeSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE, "spatial relative");
            try {
                AL10.alSource3f(sourceId, AL10.AL_POSITION, 0.0f, 0.0f, 0.0f);
                checkOpenALError("alSource3f AL_POSITION");
            } catch (Throwable t) {
                ClientAudioDebugLogger.error("Failed alSource3f AL_POSITION: " + t.getMessage());
            }
        }

        // Apply combined volumes
        PlayingSound sound = new PlayingSound(soundId, sourceCategory, sourceId, bufferId, positional, x, y, z, volume, minVolume);
        float masterVol = getCategoryVolume("master");
        float categoryVol = getCategoryVolume(sourceCategory);
        float effectiveVolume = calculateEffectiveVolume(sound);
        safeSourcef(sourceId, AL10.AL_GAIN, effectiveVolume, "gain");

        try {
            AL10.alSourcePlay(sourceId);
            checkOpenALError("alSourcePlay");
        } catch (Throwable t) {
            ClientAudioDebugLogger.error("Failed alSourcePlay: " + t.getMessage());
        }
        ClientAudioDebugLogger.info("[VoiceControl] OpenAL play started: sound=" + soundId + ", source=" + sourceId + ", buffer=" + bufferId);
        ClientAudioDebugLogger.info("[VoiceControl] Volume resolved: sound=" + soundId + ", category=" + sourceCategory + ", base=" + volume + ", master=" + masterVol + ", categoryVolume=" + categoryVol + ", min=" + minVolume + ", final=" + effectiveVolume);

        playingSounds.add(sound);
    }

    public static void stopSounds(String soundId, String category) {
        synchronized (playingSounds) {
            for (PlayingSound sound : playingSounds) {
                boolean matchSound = (soundId == null || sound.soundId.equalsIgnoreCase(soundId));
                boolean matchCategory = (category == null || sound.sourceCategory.equalsIgnoreCase(category));
                
                if (matchSound && matchCategory) {
                    requestStopAndDelayedCleanup(sound);
                }
            }
        }
    }

    public static void updateVolumeSettings() {
        synchronized (playingSounds) {
            for (PlayingSound sound : playingSounds) {
                try {
                    if (safeIsSource(sound.alSourceId, "updateVolumeSettings")) {
                        float effectiveVolume = calculateEffectiveVolume(sound);
                        safeSourcef(sound.alSourceId, AL10.AL_GAIN, effectiveVolume, "updateVolumeSettings gain");
                    }
                } catch (Throwable t) {
                    ClientAudioDebugLogger.error("Failed to update OpenAL volume in updateVolumeSettings for " + sound.soundId + ": " + t.getMessage());
                }
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
                try {
                    AL10.alListener3f(AL10.AL_POSITION, (float) pos.x, (float) pos.y, (float) pos.z);
                    float[] orientation = new float[]{look.x, look.y, look.z, up.x, up.y, up.z};
                    AL10.alListenerfv(AL10.AL_ORIENTATION, orientation);
                } catch (Throwable t) {
                    ClientAudioDebugLogger.errorOnceEvery("listener-update-err", "Failed to update OpenAL listener position/orientation: " + t.getMessage(), 10000);
                }
            }
        }

        // Clean up playing sounds that have finished
        synchronized (playingSounds) {
            Iterator<PlayingSound> it = playingSounds.iterator();
            while (it.hasNext()) {
                PlayingSound sound = it.next();
                if (!safeIsSource(sound.alSourceId, "clientTick validity check")) {
                    it.remove();
                    continue;
                }

                int state = safeGetSourceState(sound.alSourceId, "clientTick state check");

                if (state == AL10.AL_STOPPED && !sound.pendingCleanup) {
                    ClientAudioDebugLogger.info("[VoiceControl] OpenAL sound stopped naturally: sound=" + sound.soundId + ", source=" + sound.alSourceId + ", buffer=" + sound.alBufferId);
                    sound.pendingCleanup = true;
                    sound.cleanupTicks = 0;
                }

                if (sound.pendingCleanup) {
                    sound.cleanupTicks++;
                    int delay = Config.SERVER.dynamicSoundCleanupDelayTicks.get();
                    if (sound.cleanupTicks >= delay) {
                        executeDelayedCleanup(sound);
                        it.remove();
                    }
                }
            }
        }

        // Update volumes/gains of all active playing sounds
        synchronized (playingSounds) {
            for (PlayingSound sound : playingSounds) {
                try {
                    if (safeIsSource(sound.alSourceId, "clientTick volume update")) {
                        float effectiveVolume = calculateEffectiveVolume(sound);
                        safeSourcef(sound.alSourceId, AL10.AL_GAIN, effectiveVolume, "clientTick volume update gain");
                    }
                } catch (Throwable t) {
                    ClientAudioDebugLogger.errorOnceEvery("vol-update-err-" + sound.soundId, "Failed to update OpenAL volume for " + sound.soundId + ": " + t.getMessage(), 10000);
                }
            }
        }
    }

    public static void cleanup() {
        mainThreadQueue.clear();
        synchronized (playingSounds) {
            for (PlayingSound sound : playingSounds) {
                executeDelayedCleanup(sound);
            }
            playingSounds.clear();
        }
    }

    private static float getCategoryVolume(String categoryName) {
        try {
            SoundSource source = SoundSource.valueOf(categoryName.toUpperCase(Locale.ROOT));
            return Minecraft.getInstance().options.getSoundSourceVolume(source);
        } catch (IllegalArgumentException e) {
            ClientAudioDebugLogger.warnOnceEvery("unknown-category-" + categoryName, "Unknown sound category '" + categoryName + "', falling back to master volume.", 10000);
            try {
                return Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER);
            } catch (Exception ex) {
                return 1.0f;
            }
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
