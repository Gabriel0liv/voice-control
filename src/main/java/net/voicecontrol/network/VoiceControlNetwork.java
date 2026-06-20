package net.voicecontrol.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.voicecontrol.VoiceControlMod;
import net.voicecontrol.network.packets.*;

public class VoiceControlNetwork {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(VoiceControlMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void init() {
        int id = 0;
        
        // Client -> Server
        CHANNEL.registerMessage(id++, AudioClientReadyPacket.class, AudioClientReadyPacket::encode, AudioClientReadyPacket::decode, AudioClientReadyPacket::handle);
        CHANNEL.registerMessage(id++, AudioRequestPacket.class, AudioRequestPacket::encode, AudioRequestPacket::decode, AudioRequestPacket::handle);
        CHANNEL.registerMessage(id++, AudioClientSyncStatusPacket.class, AudioClientSyncStatusPacket::encode, AudioClientSyncStatusPacket::decode, AudioClientSyncStatusPacket::handle);

        // Server -> Client
        CHANNEL.registerMessage(id++, AudioManifestPacket.class, AudioManifestPacket::encode, AudioManifestPacket::decode, AudioManifestPacket::handle);
        CHANNEL.registerMessage(id++, AudioChunkPacket.class, AudioChunkPacket::encode, AudioChunkPacket::decode, AudioChunkPacket::handle);
        CHANNEL.registerMessage(id++, AudioSyncCompletePacket.class, AudioSyncCompletePacket::encode, AudioSyncCompletePacket::decode, AudioSyncCompletePacket::handle);
        CHANNEL.registerMessage(id++, DynamicSoundPlayPacket.class, DynamicSoundPlayPacket::encode, DynamicSoundPlayPacket::decode, DynamicSoundPlayPacket::handle);
        CHANNEL.registerMessage(id++, DynamicSoundStopPacket.class, DynamicSoundStopPacket::encode, DynamicSoundStopPacket::decode, DynamicSoundStopPacket::handle);
    }

    public static <MSG> void sendToClient(MSG message, ServerPlayer player) {
        CHANNEL.sendTo(message, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    public static <MSG> void sendToServer(MSG message) {
        CHANNEL.sendToServer(message);
    }
}
