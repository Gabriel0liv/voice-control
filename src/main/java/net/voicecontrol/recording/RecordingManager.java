package net.voicecontrol.recording;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.voicecontrol.Config;
import net.voicecontrol.logging.AdminLogger;

import java.io.IOException;
import java.util.*;

public class RecordingManager {
    private static final Map<UUID, MicRecordingSession> activeMicSessions = new HashMap<>();
    private static final Map<UUID, MonitorRecordingSession> activeMonitorSessions = new HashMap<>();
    
    private static boolean allMicActive = false;
    private static String allMicStartedBy = "SYSTEM";

    public static synchronized boolean startMicRecording(ServerPlayer player, String startedBy) {
        if (!Config.SERVER.recordingEnabled.get()) return false;
        
        UUID uuid = player.getUUID();
        if (activeMicSessions.containsKey(uuid)) {
            return false; // already recording
        }

        try {
            MicRecordingSession session = new MicRecordingSession(uuid, player.getGameProfile().getName(), startedBy);
            activeMicSessions.put(uuid, session);
            return true;
        } catch (IOException e) {
            AdminLogger.error(startedBy, "Failed to start direct mic recording for " + player.getGameProfile().getName() + ": " + e.getMessage());
            return false;
        }
    }

    public static synchronized boolean stopMicRecording(ServerPlayer player) {
        UUID uuid = player.getUUID();
        MicRecordingSession session = activeMicSessions.remove(uuid);
        if (session != null) {
            session.stop();
            return true;
        }
        return false;
    }

    public static synchronized void startAllMicRecording(MinecraftServer server, String startedBy) {
        if (!Config.SERVER.recordingEnabled.get()) return;
        
        allMicActive = true;
        allMicStartedBy = startedBy;
        
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            startMicRecording(player, startedBy);
        }
        AdminLogger.info(startedBy, "Started direct mic recording for all online players.");
    }

    public static synchronized void stopAllMicRecording(MinecraftServer server, String stoppedBy) {
        allMicActive = false;
        
        List<MicRecordingSession> sessions = new ArrayList<>(activeMicSessions.values());
        for (MicRecordingSession session : sessions) {
            activeMicSessions.remove(session.getPlayerUuid());
            session.stop();
        }
        AdminLogger.info(stoppedBy, "Stopped direct mic recording for all players.");
    }

    public static synchronized boolean startMonitorRecording(MinecraftServer server, ServerPlayer player, String startedBy) {
        if (!Config.SERVER.recordingEnabled.get()) return false;
        
        UUID uuid = player.getUUID();
        if (activeMonitorSessions.containsKey(uuid)) {
            return false; // already recording
        }

        try {
            MonitorRecordingSession session = new MonitorRecordingSession(server, uuid, player.getGameProfile().getName(), startedBy);
            activeMonitorSessions.put(uuid, session);
            return true;
        } catch (IOException e) {
            AdminLogger.error(startedBy, "Failed to start monitor recording for " + player.getGameProfile().getName() + ": " + e.getMessage());
            return false;
        }
    }

    public static synchronized boolean stopMonitorRecording(ServerPlayer player) {
        UUID uuid = player.getUUID();
        MonitorRecordingSession session = activeMonitorSessions.remove(uuid);
        if (session != null) {
            session.stop();
            return true;
        }
        return false;
    }

    public static synchronized void stopAll(CommandSourceStack source) {
        String stoppedBy = source.getTextName();
        allMicActive = false;
        
        // Stop direct mic recordings
        List<MicRecordingSession> micSessions = new ArrayList<>(activeMicSessions.values());
        for (MicRecordingSession s : micSessions) {
            s.stop();
        }
        activeMicSessions.clear();

        // Stop monitored recordings
        List<MonitorRecordingSession> monSessions = new ArrayList<>(activeMonitorSessions.values());
        for (MonitorRecordingSession s : monSessions) {
            s.stop();
        }
        activeMonitorSessions.clear();
        
        AdminLogger.info(stoppedBy, "All active voice recording sessions stopped due to server shutdown or request.");
    }

    public static void handleMicPacket(UUID playerUuid, String name, byte[] opusData) {
        MicRecordingSession session;
        synchronized (RecordingManager.class) {
            session = activeMicSessions.get(playerUuid);
        }
        if (session != null) {
            session.processPacket(opusData);
        }
    }

    public static synchronized void handlePlayerJoin(Player player) {
        if (player instanceof ServerPlayer && allMicActive && Config.SERVER.recordingAutoRecordNewPlayersWhenAll.get()) {
            startMicRecording((ServerPlayer) player, allMicStartedBy);
        }
    }

    public static synchronized void handlePlayerLeave(Player player) {
        if (player instanceof ServerPlayer) {
            ServerPlayer serverPlayer = (ServerPlayer) player;
            if (Config.SERVER.recordingAutoStopOnDisconnect.get()) {
                stopMicRecording(serverPlayer);
                stopMonitorRecording(serverPlayer);
            }
        }
    }

    public static synchronized Collection<MicRecordingSession> getActiveMicSessions() {
        return new ArrayList<>(activeMicSessions.values());
    }

    public static synchronized Collection<MonitorRecordingSession> getActiveMonitorSessions() {
        return new ArrayList<>(activeMonitorSessions.values());
    }

    public static boolean isAllMicActive() {
        return allMicActive;
    }
}
