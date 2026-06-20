package net.voicecontrol;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import net.voicecontrol.audio.AudioImportManager;
import net.voicecontrol.commands.VoiceControlCommands;
import net.voicecontrol.logging.AdminLogger;
import net.voicecontrol.pack.ResourcePackServer;
import net.voicecontrol.recording.RecordingManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod(VoiceControlMod.MOD_ID)
public class VoiceControlMod {
    public static final String MOD_ID = "voicecontrol";
    public static final Logger LOGGER = LogManager.getLogger("VoiceControl");

    public VoiceControlMod() {
        // Register Config
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, "voicecontrol-server.toml");

        // Initialize directories
        initializeDirectories();

        // Initialize Admin Logger
        AdminLogger.info("SYSTEM", "Initializing VoiceControl mod.");

        // Register event handlers
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void initializeDirectories() {
        Path base = getBaseFolder();
        try {
            Files.createDirectories(base);
            Files.createDirectories(base.resolve("recordings"));
            Files.createDirectories(base.resolve("recordings/players"));
            Files.createDirectories(base.resolve("recordings/monitors"));
            Files.createDirectories(base.resolve("imported-audios"));
            Files.createDirectories(base.resolve("resourcepack"));
            Files.createDirectories(base.resolve("resourcepack/build"));
            Files.createDirectories(base.resolve("resourcepack/cache"));
            Files.createDirectories(base.resolve("logs"));
        } catch (IOException e) {
            LOGGER.error("Failed to create VoiceControl directories", e);
        }
    }

    public static Path getBaseFolder() {
        return FMLPaths.GAMEDIR.get().resolve("voice-control");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        AdminLogger.info("SYSTEM", "Server is starting. Loading VoiceControl services.");
        
        // Start resource pack server if enabled
        if (Config.SERVER.resourcePackEnabled.get() && Config.SERVER.resourcePackInternalHttpServer.get()) {
            ResourcePackServer.startServer();
        }

        // Initialize sound database and load files
        if (Config.SERVER.audioImportEnabled.get()) {
            AudioImportManager.reloadAudios(event.getServer(), null);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        AdminLogger.info("SYSTEM", "Server is stopping. Cleaning up VoiceControl sessions and services.");
        
        // Stop all recording sessions
        RecordingManager.stopAll(event.getServer().createCommandSourceStack());

        // Stop HTTP server
        ResourcePackServer.stopServer();
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // If "all" mode is active and autoRecordNewPlayersWhenAll is enabled, start recording this player
        RecordingManager.handlePlayerJoin(event.getEntity());
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // If autoStopOnDisconnect is enabled, stop recording sessions for this player
        RecordingManager.handlePlayerLeave(event.getEntity());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        VoiceControlCommands.register(event.getDispatcher());
        LOGGER.info("Registered VoiceControl commands.");
    }
}
