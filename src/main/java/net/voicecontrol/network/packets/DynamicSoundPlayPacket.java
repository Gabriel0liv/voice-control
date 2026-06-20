package net.voicecontrol.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class DynamicSoundPlayPacket {
    private final String soundId;
    private final String source;
    private final boolean positional;
    private final double x;
    private final double y;
    private final double z;
    private final float volume;
    private final float pitch;
    private final float minVolume;
    private final float attenuation;

    public DynamicSoundPlayPacket(String soundId, String source, boolean positional, double x, double y, double z, float volume, float pitch, float minVolume, float attenuation) {
        this.soundId = soundId;
        this.source = source;
        this.positional = positional;
        this.x = x;
        this.y = y;
        this.z = z;
        this.volume = volume;
        this.pitch = pitch;
        this.minVolume = minVolume;
        this.attenuation = attenuation;
    }

    public String getSoundId() { return soundId; }
    public String getSource() { return source; }
    public boolean isPositional() { return positional; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getVolume() { return volume; }
    public float getPitch() { return pitch; }
    public float getMinVolume() { return minVolume; }
    public float getAttenuation() { return attenuation; }

    public static void encode(DynamicSoundPlayPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.soundId);
        buf.writeUtf(msg.source);
        buf.writeBoolean(msg.positional);
        if (msg.positional) {
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeFloat(msg.attenuation);
        }
        buf.writeFloat(msg.volume);
        buf.writeFloat(msg.pitch);
        buf.writeFloat(msg.minVolume);
    }

    public static DynamicSoundPlayPacket decode(FriendlyByteBuf buf) {
        String soundId = buf.readUtf();
        String source = buf.readUtf();
        boolean positional = buf.readBoolean();
        double x = 0;
        double y = 0;
        double z = 0;
        float attenuation = 1.0f;
        if (positional) {
            x = buf.readDouble();
            y = buf.readDouble();
            z = buf.readDouble();
            attenuation = buf.readFloat();
        }
        float volume = buf.readFloat();
        float pitch = buf.readFloat();
        float minVolume = buf.readFloat();
        return new DynamicSoundPlayPacket(soundId, source, positional, x, y, z, volume, pitch, minVolume, attenuation);
    }

    public static void handle(DynamicSoundPlayPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                net.voicecontrol.client.ClientPacketHandler.handlePlaySound(
                        msg.getSoundId(),
                        msg.getSource(),
                        msg.isPositional(),
                        msg.getX(),
                        msg.getY(),
                        msg.getZ(),
                        msg.getVolume(),
                        msg.getPitch(),
                        msg.getMinVolume(),
                        msg.getAttenuation()
                );
            });
        });
        ctx.setPacketHandled(true);
    }
}
