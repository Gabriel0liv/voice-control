package net.voicecontrol.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class AudioChunkPacket {
    private final String id;
    private final String sha256;
    private final int chunkIndex;
    private final int totalChunks;
    private final byte[] data;

    public AudioChunkPacket(String id, String sha256, int chunkIndex, int totalChunks, byte[] data) {
        this.id = id;
        this.sha256 = sha256;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.data = data;
    }

    public String getId() {
        return id;
    }

    public String getSha256() {
        return sha256;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public byte[] getData() {
        return data;
    }

    public static void encode(AudioChunkPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.id);
        buf.writeUtf(msg.sha256);
        buf.writeInt(msg.chunkIndex);
        buf.writeInt(msg.totalChunks);
        buf.writeByteArray(msg.data);
    }

    public static AudioChunkPacket decode(FriendlyByteBuf buf) {
        return new AudioChunkPacket(
                buf.readUtf(),
                buf.readUtf(),
                buf.readInt(),
                buf.readInt(),
                buf.readByteArray()
        );
    }

    public static void handle(AudioChunkPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                net.voicecontrol.client.ClientPacketHandler.handleChunk(
                        msg.getId(),
                        msg.getSha256(),
                        msg.getChunkIndex(),
                        msg.getTotalChunks(),
                        msg.getData()
                );
            });
        });
        ctx.setPacketHandled(true);
    }
}
