package net.voicecontrol.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.voicecontrol.audio.AudioImportManager;

import java.util.function.Supplier;

public class AudioClientReadyPacket {
    public AudioClientReadyPacket() {}

    public static void encode(AudioClientReadyPacket msg, FriendlyByteBuf buf) {}

    public static AudioClientReadyPacket decode(FriendlyByteBuf buf) {
        return new AudioClientReadyPacket();
    }

    public static void handle(AudioClientReadyPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                AudioImportManager.registerReadyPlayer(player);
            }
        });
        ctx.setPacketHandled(true);
    }
}
