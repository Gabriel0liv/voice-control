package net.voicecontrol;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import net.voicecontrol.logging.AdminLogger;
import net.voicecontrol.recording.RecordingManager;

@ForgeVoicechatPlugin
public class VoiceControlVoicePlugin implements VoicechatPlugin {
    private static VoicechatServerApi serverApi;

    @Override
    public String getPluginId() {
        return VoiceControlMod.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi) {
            serverApi = (VoicechatServerApi) api;
            AdminLogger.info("SYSTEM", "Simple Voice Chat Server API loaded successfully.");
        } else {
            AdminLogger.warn("SYSTEM", "Failed to load Simple Voice Chat Server API (running on client?).");
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (!Config.SERVER.recordingEnabled.get()) {
            return;
        }

        VoicechatConnection connection = event.getSenderConnection();
        if (connection == null) {
            return;
        }

        de.maxhenkel.voicechat.api.Player voicePlayer = connection.getPlayer();
        if (voicePlayer == null) {
            return;
        }

        String name = "Unknown";
        if (voicePlayer instanceof de.maxhenkel.voicechat.api.ServerPlayer) {
            Object nativePlayer = ((de.maxhenkel.voicechat.api.ServerPlayer) voicePlayer).getPlayer();
            if (nativePlayer instanceof net.minecraft.server.level.ServerPlayer) {
                name = ((net.minecraft.server.level.ServerPlayer) nativePlayer).getGameProfile().getName();
            }
        }
        if ("Unknown".equals(name)) {
            name = voicePlayer.getUuid().toString();
        }

        MicrophonePacket packet = event.getPacket();
        byte[] opusData = packet.getOpusEncodedData();

        // Pass to RecordingManager for processing
        RecordingManager.handleMicPacket(voicePlayer.getUuid(), name, opusData);
    }

    public static VoicechatServerApi getServerApi() {
        return serverApi;
    }
}
