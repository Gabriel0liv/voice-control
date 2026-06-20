package net.voicecontrol.client;

import net.voicecontrol.network.VoiceControlNetwork;
import net.voicecontrol.network.packets.AudioClientSyncStatusPacket;
import net.voicecontrol.network.packets.AudioManifestPacket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClientPacketHandler {

    public static void handleManifest(List<AudioManifestPacket.ManifestEntry> entries) {
        Map<String, String> serverManifest = new HashMap<>();
        for (AudioManifestPacket.ManifestEntry entry : entries) {
            serverManifest.put(entry.id, entry.sha256);
        }
        
        // Update client manifest, request missing files, and send sync status for cached files
        DynamicClientSoundEngine.updateManifestFromServer(serverManifest);
        
        // Check local files and report status
        for (AudioManifestPacket.ManifestEntry entry : entries) {
            if (DynamicClientSoundEngine.getClientManifest().containsKey(entry.id)) {
                VoiceControlNetwork.sendToServer(new AudioClientSyncStatusPacket(entry.id, entry.sha256, true));
            }
        }
    }

    public static void handleChunk(String soundId, String sha256, int chunkIndex, int totalChunks, byte[] data) {
        DynamicClientSoundEngine.handleChunk(soundId, sha256, chunkIndex, totalChunks, data);
    }

    public static void handleSyncComplete(String soundId, String sha256, boolean success) {
        boolean cached = DynamicClientSoundEngine.handleSyncComplete(soundId, sha256, success);
        VoiceControlNetwork.sendToServer(new AudioClientSyncStatusPacket(soundId, sha256, cached));
    }

    public static void handlePlaySound(String soundId, String source, boolean positional, double x, double y, double z, float volume, float pitch, float minVolume, float attenuation) {
        DynamicClientSoundEngine.playSound(soundId, source, positional, x, y, z, volume, pitch, minVolume, attenuation);
    }

    public static void handleStopSound(String soundId, String category) {
        DynamicClientSoundEngine.stopSounds(soundId, category);
    }
}
