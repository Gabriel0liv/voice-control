package net.voicecontrol.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.voicecontrol.audio.AudioImportManager;

import java.util.function.Supplier;

public class AudioClientSyncStatusPacket {
    private final String id;
    private final String sha256;
    private final boolean cached;

    public AudioClientSyncStatusPacket(String id, String sha256, boolean cached) {
        this.id = id;
        this.sha256 = sha256;
        this.cached = cached;
    }

    public String getId() {
        return id;
    }

    public String getSha256() {
        return sha256;
    }

    public boolean isCached() {
        return cached;
    }

    public static void encode(AudioClientSyncStatusPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.id);
        buf.writeUtf(msg.sha256);
        buf.writeBoolean(msg.cached);
    }

    public static AudioClientSyncStatusPacket decode(FriendlyByteBuf buf) {
        return new AudioClientSyncStatusPacket(buf.readUtf(), buf.readUtf(), buf.readBoolean());
    }

    public static void handle(AudioClientSyncStatusPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                AudioImportManager.updatePlayerSyncStatus(player, msg.getId(), msg.getSha256(), msg.isCached());
            }
        });
        ctx.setPacketHandled(true);
    }
}
