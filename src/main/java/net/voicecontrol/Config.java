package net.voicecontrol;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

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

        // Command configurations
        public final ForgeConfigSpec.IntValue commandsPermissionLevel;

        // Audio Import configurations
        public final ForgeConfigSpec.BooleanValue audioImportEnabled;
        public final ForgeConfigSpec.ConfigValue<String> audioImportInputFolder;
        public final ForgeConfigSpec.ConfigValue<String> audioImportNamespace;
        public final ForgeConfigSpec.BooleanValue audioImportConvertToOgg;

        // Resource pack configurations
        public final ForgeConfigSpec.BooleanValue resourcePackEnabled;
        public final ForgeConfigSpec.BooleanValue resourcePackAutoBuildOnReload;
        public final ForgeConfigSpec.BooleanValue resourcePackInternalHttpServer;
        public final ForgeConfigSpec.IntValue resourcePackHttpPort;
        public final ForgeConfigSpec.ConfigValue<String> resourcePackPublicUrl;
        public final ForgeConfigSpec.BooleanValue resourcePackAllowLocalIpAutoDetect;
        public final ForgeConfigSpec.BooleanValue resourcePackRequired;

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
            builder.pop();

            builder.push("commands");
            commandsPermissionLevel = builder
                    .comment("OP permission level required to run mod commands")
                    .defineInRange("permissionLevel", 3, 0, 4);
            builder.pop();

            builder.push("audioImport");
            audioImportEnabled = builder
                    .comment("Enable custom audio import feature")
                    .define("enabled", true);
            audioImportInputFolder = builder
                    .comment("Folder relative to server root containing audios to import")
                    .define("inputFolder", "voice-control/imported-audios");
            audioImportNamespace = builder
                    .comment("ResourceLocation namespace to use for registered sound events")
                    .define("namespace", "voicecontrol");
            audioImportConvertToOgg = builder
                    .comment("Whether to automatically convert imported mp3/wav to ogg format using ffmpeg")
                    .define("convertToOgg", true);
            builder.pop();

            builder.push("resourcePack");
            resourcePackEnabled = builder
                    .comment("Enable generation of server resource pack for imported audios")
                    .define("resourcePackEnabled", true);
            resourcePackAutoBuildOnReload = builder
                    .comment("Automatically rebuild resource pack ZIP file on reload")
                    .define("autoBuildOnReload", true);
            resourcePackInternalHttpServer = builder
                    .comment("Serve the built resource pack using a built-in HTTP server")
                    .define("internalHttpServer", true);
            resourcePackHttpPort = builder
                    .comment("Port for the internal HTTP server")
                    .defineInRange("httpPort", 8087, 1, 65535);
            resourcePackPublicUrl = builder
                    .comment("Public URL of the resource pack. If empty, the server attempts to detect client connection address.")
                    .define("publicUrl", "");
            resourcePackAllowLocalIpAutoDetect = builder
                    .comment("Allow automatic detection of local IP address if publicUrl is empty. If false, pushing without publicUrl will fail.")
                    .define("allowLocalIpAutoDetect", false);
            resourcePackRequired = builder
                    .comment("Whether players are prompted/forced to download the resource pack to play")
                    .define("required", false);
            builder.pop();
        }
    }
}
