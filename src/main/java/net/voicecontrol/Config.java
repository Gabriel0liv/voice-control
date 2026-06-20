package net.voicecontrol;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;
import java.util.List;
import java.util.Arrays;

public class Config {
    public static final ForgeConfigSpec SPEC;
    public static final ServerConfig SERVER;

    static {
        Pair<ServerConfig, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(ServerConfig::new);
        SERVER = specPair.getLeft();
        SPEC = specPair.getRight();
    }

    public static class ServerConfig {
        // Recording configurations
        public final ForgeConfigSpec.BooleanValue recordingEnabled;
        public final ForgeConfigSpec.ConfigValue<String> recordingDefaultFormat;
        public final ForgeConfigSpec.ConfigValue<String> recordingFallbackFormat;
        public final ForgeConfigSpec.BooleanValue recordingSaveMetadata;
        public final ForgeConfigSpec.BooleanValue recordingSaveHash;
        public final ForgeConfigSpec.BooleanValue recordingAutoStopOnDisconnect;
        public final ForgeConfigSpec.BooleanValue recordingAutoRecordNewPlayersWhenAll;
        public final ForgeConfigSpec.IntValue recordingMaxRecordingMinutes;
        public final ForgeConfigSpec.BooleanValue recordingOrganizeByDateAndSession;
        public final ForgeConfigSpec.ConfigValue<String> recordingDateFolderPattern;
        public final ForgeConfigSpec.ConfigValue<String> recordingSessionFolderPattern;

        // Command configurations
        public final ForgeConfigSpec.IntValue commandsPermissionLevel;

        // Audio Library configurations
        public final ForgeConfigSpec.BooleanValue audioLibraryEnabled;
        public final ForgeConfigSpec.ConfigValue<String> audioLibraryImportFolder;
        public final ForgeConfigSpec.BooleanValue audioLibraryAllowMp3WavTranscode;
        public final ForgeConfigSpec.ConfigValue<String> audioLibraryFfmpegPath;
        public final ForgeConfigSpec.BooleanValue audioLibrarySyncOnPlayerJoin;
        public final ForgeConfigSpec.BooleanValue audioLibrarySyncOnAudioReload;
        public final ForgeConfigSpec.IntValue audioLibraryMaxChunkSizeBytes;
        public final ForgeConfigSpec.IntValue audioLibraryMaxAudioFileSizeMb;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> audioLibraryAllowedExtensions;

        // Dynamic Sound configurations
        public final ForgeConfigSpec.BooleanValue dynamicSoundEnabled;
        public final ForgeConfigSpec.BooleanValue dynamicSoundClientCacheEnabled;
        public final ForgeConfigSpec.DoubleValue dynamicSoundDefaultVolume;
        public final ForgeConfigSpec.DoubleValue dynamicSoundDefaultPitch;
        public final ForgeConfigSpec.IntValue dynamicSoundMaxConcurrentSounds;
        public final ForgeConfigSpec.BooleanValue dynamicSoundPreloadOnJoin;
        public final ForgeConfigSpec.BooleanValue dynamicSoundPlayAfterDownloadIfMissing;
        public final ForgeConfigSpec.BooleanValue dynamicSoundDebugDisableOpenALCleanup;
        public final ForgeConfigSpec.IntValue dynamicSoundDedupeWindowTicks;
        public final ForgeConfigSpec.BooleanValue dynamicSoundStopExistingIdenticalBeforePlay;
        public final ForgeConfigSpec.IntValue dynamicSoundCleanupDelayTicks;
        public final ForgeConfigSpec.BooleanValue dynamicSoundUseOpenALPlayback;

        // Voice Playback configurations
        public final ForgeConfigSpec.BooleanValue voicePlaybackEnabled;
        public final ForgeConfigSpec.IntValue voicePlaybackDefaultDistance;
        public final ForgeConfigSpec.DoubleValue voicePlaybackDefaultVolume;
        public final ForgeConfigSpec.IntValue voicePlaybackMaxDurationSeconds;
        public final ForgeConfigSpec.BooleanValue voicePlaybackPreDecodePcmOnReload;

        public ServerConfig(ForgeConfigSpec.Builder builder) {
            builder.push("recording");
            recordingEnabled = builder
                    .comment("Enable voice recording features")
                    .define("enabled", true);
            recordingDefaultFormat = builder
                    .comment("Default format for voice recordings (mp3 or wav)")
                    .define("defaultFormat", "mp3");
            recordingFallbackFormat = builder
                    .comment("Fallback format if the default is unavailable")
                    .define("fallbackFormat", "wav");
            recordingSaveMetadata = builder
                    .comment("Save JSON metadata file for each recording")
                    .define("saveMetadata", true);
            recordingSaveHash = builder
                    .comment("Save SHA-256 hash file for each recording")
                    .define("saveHash", true);
            recordingAutoStopOnDisconnect = builder
                    .comment("Automatically stop recording a player when they disconnect")
                    .define("autoStopOnDisconnect", true);
            recordingAutoRecordNewPlayersWhenAll = builder
                    .comment("Automatically start recording new players joining while '/voicectl rec mic start all' is active")
                    .define("autoRecordNewPlayersWhenAll", true);
            recordingMaxRecordingMinutes = builder
                    .comment("Maximum recording duration in minutes (0 to disable limit)")
                    .defineInRange("maxRecordingMinutes", 60, 0, 1440);
            recordingOrganizeByDateAndSession = builder
                    .comment("Organize recordings by date and session directories")
                    .define("organizeByDateAndSession", true);
            recordingDateFolderPattern = builder
                    .comment("Pattern for the date subfolder name")
                    .define("dateFolderPattern", "dd-MM-yyyy");
            recordingSessionFolderPattern = builder
                    .comment("Pattern for the session subfolder name")
                    .define("sessionFolderPattern", "dd-MM-yyyy_HH-mm-ss");
            builder.pop();

            builder.push("commands");
            commandsPermissionLevel = builder
                    .comment("OP permission level required to run mod commands")
                    .defineInRange("permissionLevel", 3, 0, 4);
            builder.pop();

            builder.push("audioLibrary");
            audioLibraryEnabled = builder
                    .comment("Enable custom audio library features")
                    .define("enabled", true);
            audioLibraryImportFolder = builder
                    .comment("Folder containing custom audio to import")
                    .define("importFolder", "imported-audios");
            audioLibraryAllowMp3WavTranscode = builder
                    .comment("Allow automatic transcoding of wav/mp3 to ogg using ffmpeg")
                    .define("allowMp3WavTranscode", true);
            audioLibraryFfmpegPath = builder
                    .comment("Optional path to ffmpeg executable (leave empty to search in system path)")
                    .define("ffmpegPath", "");
            audioLibrarySyncOnPlayerJoin = builder
                    .comment("Sync audio manifest to players when they join")
                    .define("syncOnPlayerJoin", true);
            audioLibrarySyncOnAudioReload = builder
                    .comment("Sync audio manifest to players on reload")
                    .define("syncOnAudioReload", true);
            audioLibraryMaxChunkSizeBytes = builder
                    .comment("Maximum chunk size in bytes for sync packet transfer")
                    .defineInRange("maxChunkSizeBytes", 32768, 1024, 1048576);
            audioLibraryMaxAudioFileSizeMb = builder
                    .comment("Maximum allowed audio file size in megabytes")
                    .defineInRange("maxAudioFileSizeMb", 20, 1, 500);
            audioLibraryAllowedExtensions = builder
                    .comment("List of allowed extensions for audio import")
                    .defineList("allowedExtensions", Arrays.asList("ogg", "wav", "mp3"), s -> s instanceof String);
            builder.pop();

            builder.push("dynamicSound");
            dynamicSoundEnabled = builder
                    .comment("Enable dynamic sound play engine")
                    .define("enabled", true);
            dynamicSoundClientCacheEnabled = builder
                    .comment("Enable client caching of synchronized audios")
                    .define("clientCacheEnabled", true);
            dynamicSoundDefaultVolume = builder
                    .comment("Default volume for played sounds")
                    .defineInRange("defaultVolume", 1.0, 0.0, 10.0);
            dynamicSoundDefaultPitch = builder
                    .comment("Default pitch for played sounds")
                    .defineInRange("defaultPitch", 1.0, 0.5, 2.0);
            dynamicSoundMaxConcurrentSounds = builder
                    .comment("Maximum concurrent playing dynamic sounds on client")
                    .defineInRange("maxConcurrentSounds", 32, 1, 128);
            dynamicSoundPreloadOnJoin = builder
                    .comment("Pre-download all manifest sounds on join (not recommended for large libraries)")
                    .define("preloadOnJoin", false);
            dynamicSoundPlayAfterDownloadIfMissing = builder
                    .comment("Play a sound immediately after downloading if it was requested but missing")
                    .define("playAfterDownloadIfMissing", true);
            dynamicSoundDebugDisableOpenALCleanup = builder
                    .comment("Debug mode: disable OpenAL resource cleanup upon completion")
                    .define("debugDisableOpenALCleanup", false);
            dynamicSoundDedupeWindowTicks = builder
                    .comment("Number of ticks during which identical playsound commands are considered duplicates and ignored. Use 0 to disable.")
                    .defineInRange("dedupeWindowTicks", 10, 0, 1200);
            dynamicSoundStopExistingIdenticalBeforePlay = builder
                    .comment("If true, stop any existing identical sound before starting a new one to avoid overlay.")
                    .define("stopExistingIdenticalBeforePlay", true);
            dynamicSoundCleanupDelayTicks = builder
                    .comment("Delayed cleanup ticks before OpenAL sources/buffers are deleted.")
                    .defineInRange("cleanupDelayTicks", 40, 1, 1200);
            dynamicSoundUseOpenALPlayback = builder
                    .comment("Use custom OpenAL playback engine for playsound commands. If false, disables client audio playback.")
                    .define("useOpenALPlayback", true);
            builder.pop();

            builder.push("voicePlayback");
            voicePlaybackEnabled = builder
                    .comment("Enable playback of sounds over simple voice chat")
                    .define("enabled", true);
            voicePlaybackDefaultDistance = builder
                    .comment("Default voice distance for locational playback")
                    .defineInRange("defaultDistance", 48, 1, 1000);
            voicePlaybackDefaultVolume = builder
                    .comment("Default volume for voice chat playback")
                    .defineInRange("defaultVolume", 1.0, 0.0, 10.0);
            voicePlaybackMaxDurationSeconds = builder
                    .comment("Maximum duration in seconds for voice chat playback")
                    .defineInRange("maxDurationSeconds", 120, 1, 3600);
            voicePlaybackPreDecodePcmOnReload = builder
                    .comment("Pre-decode and cache PCM data for voice playback on reload")
                    .define("preDecodePcmOnReload", false);
            builder.pop();
        }
    }
}
