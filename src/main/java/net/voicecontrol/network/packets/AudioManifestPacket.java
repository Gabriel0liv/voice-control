package net.voicecontrol.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class AudioManifestPacket {
    public static class ManifestEntry {
        public String id;
        public String filename;
        public String sha256;
        public long sizeBytes;
        public double duration;
        public String format;
        public long lastModified;

        public ManifestEntry() {}

        public ManifestEntry(String id, String filename, String sha256, long sizeBytes, double duration, String format, long lastModified) {
            this.id = id;
            this.filename = filename;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.duration = duration;
            this.format = format;
            this.lastModified = lastModified;
        }
    }

    private final List<ManifestEntry> entries;

    public AudioManifestPacket(List<ManifestEntry> entries) {
        this.entries = entries;
    }

    public List<ManifestEntry> getEntries() {
        return entries;
    }

    public static void encode(AudioManifestPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entries.size());
        for (ManifestEntry entry : msg.entries) {
            buf.writeUtf(entry.id);
            buf.writeUtf(entry.filename);
            buf.writeUtf(entry.sha256);
            buf.writeLong(entry.sizeBytes);
            buf.writeDouble(entry.duration);
            buf.writeUtf(entry.format);
            buf.writeLong(entry.lastModified);
        }
    }

    public static AudioManifestPacket decode(FriendlyByteBuf buf) {
        int size = buf.readInt();
        List<ManifestEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ManifestEntry entry = new ManifestEntry();
            entry.id = buf.readUtf();
            entry.filename = buf.readUtf();
            entry.sha256 = buf.readUtf();
            entry.sizeBytes = buf.readLong();
            entry.duration = buf.readDouble();
            entry.format = buf.readUtf();
            entry.lastModified = buf.readLong();
            entries.add(entry);
        }
        return new AudioManifestPacket(entries);
    }

    public static void handle(AudioManifestPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                net.voicecontrol.client.ClientPacketHandler.handleManifest(msg.getEntries());
            });
        });
        ctx.setPacketHandled(true);
    }
}
