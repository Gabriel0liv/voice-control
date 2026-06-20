package net.voicecontrol.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class AudioSyncCompletePacket {
    private final String id;
    private final String sha256;
    private final boolean success;

    public AudioSyncCompletePacket(String id, String sha256, boolean success) {
        this.id = id;
        this.sha256 = sha256;
        this.success = success;
    }

    public String getId() {
        return id;
    }

    public String getSha256() {
        return sha256;
    }

    public boolean isSuccess() {
        return success;
    }

    public static void encode(AudioSyncCompletePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.id);
        buf.writeUtf(msg.sha256);
        buf.writeBoolean(msg.success);
    }

    public static AudioSyncCompletePacket decode(FriendlyByteBuf buf) {
        return new AudioSyncCompletePacket(buf.readUtf(), buf.readUtf(), buf.readBoolean());
    }

    public static void handle(AudioSyncCompletePacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                net.voicecontrol.client.ClientPacketHandler.handleSyncComplete(
                        msg.getId(),
                        msg.getSha256(),
                        msg.isSuccess()
                );
            });
        });
        ctx.setPacketHandled(true);
    }
}
