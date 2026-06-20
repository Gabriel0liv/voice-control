package net.voicecontrol.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.voicecontrol.VoiceControlMod;
import net.voicecontrol.network.VoiceControlNetwork;
import net.voicecontrol.network.packets.AudioClientReadyPacket;

@Mod.EventBusSubscriber(modid = VoiceControlMod.MOD_ID, value = Dist.CLIENT)
public class VoiceControlClient {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            DynamicClientSoundEngine.clientTick();
        }
    }

    @SubscribeEvent
    public static void onLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // Initialize client cache dir and load existing manifest
        DynamicClientSoundEngine.init(Minecraft.getInstance().gameDirectory);
        // Inform server that we are ready to receive manifest and sync audios
        VoiceControlNetwork.sendToServer(new AudioClientReadyPacket());
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        DynamicClientSoundEngine.cleanup();
    }
}
