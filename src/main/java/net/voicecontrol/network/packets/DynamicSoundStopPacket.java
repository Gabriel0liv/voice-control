package net.voicecontrol.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class DynamicSoundStopPacket {
    private final String soundId;
    private final String category;

    public DynamicSoundStopPacket(String soundId, String category) {
        this.soundId = soundId;
        this.category = category;
    }

    public String getSoundId() { return soundId; }
    public String getCategory() { return category; }

    public static void encode(DynamicSoundStopPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.soundId != null);
        if (msg.soundId != null) {
            buf.writeUtf(msg.soundId);
        }
        buf.writeBoolean(msg.category != null);
        if (msg.category != null) {
            buf.writeUtf(msg.category);
        }
    }

    public static DynamicSoundStopPacket decode(FriendlyByteBuf buf) {
        String soundId = buf.readBoolean() ? buf.readUtf() : null;
        String category = buf.readBoolean() ? buf.readUtf() : null;
        return new DynamicSoundStopPacket(soundId, category);
    }

    public static void handle(DynamicSoundStopPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                net.voicecontrol.client.ClientPacketHandler.handleStopSound(msg.getSoundId(), msg.getCategory());
            });
        });
        ctx.setPacketHandled(true);
    }
}
