package net.voicecontrol.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.voicecontrol.audio.AudioImportManager;

import java.util.function.Supplier;

public class AudioRequestPacket {
    private final String id;
    private final String sha256;

    public AudioRequestPacket(String id, String sha256) {
        this.id = id;
        this.sha256 = sha256;
    }

    public String getId() {
        return id;
    }

    public String getSha256() {
        return sha256;
    }

    public static void encode(AudioRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.id);
        buf.writeUtf(msg.sha256);
    }

    public static AudioRequestPacket decode(FriendlyByteBuf buf) {
        return new AudioRequestPacket(buf.readUtf(), buf.readUtf());
    }

    public static void handle(AudioRequestPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                AudioImportManager.streamFileToClient(player, msg.getId(), msg.getSha256());
            }
        });
        ctx.setPacketHandled(true);
    }
}
