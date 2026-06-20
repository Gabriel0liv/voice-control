package net.voicecontrol.pack;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundResourcePackPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.voicecontrol.Config;
import net.voicecontrol.VoiceControlMod;
import net.voicecontrol.logging.AdminLogger;

import java.io.File;
import java.net.InetAddress;

public class ResourcePackPusher {
    public static void pushPack(MinecraftServer server, CommandSourceStack source) {
        String operator = source != null ? source.getTextName() : "SYSTEM";
        
        File packFile = ResourcePackBuilder.getLastBuiltPack();
        if (packFile == null || !packFile.exists()) {
            packFile = VoiceControlMod.getBaseFolder().resolve("resourcepack/build/voicecontrol-pack.zip").toFile();
        }

        if (!packFile.exists()) {
            String msg = "Cannot push resource pack: Pack file does not exist. Please run '/voicectl audio reload' first.";
            AdminLogger.error(operator, msg);
            if (source != null) source.sendFailure(Component.literal("§c" + msg));
            return;
        }

        String hash = ResourcePackBuilder.getPackSha1();
        if (hash.isEmpty()) {
            hash = ResourcePackBuilder.calculateSHA1(packFile);
        }

        String url = Config.SERVER.resourcePackPublicUrl.get().trim();
        if (url.isEmpty()) {
            try {
                String ip = InetAddress.getLocalHost().getHostAddress();
                int port = Config.SERVER.resourcePackHttpPort.get();
                url = "http://" + ip + ":" + port + "/voicecontrol-pack.zip";
                
                String warnMsg = "Config 'resourcePack.publicUrl' is empty. Using auto-detected URL: " + url;
                AdminLogger.warn(operator, warnMsg);
                if (source != null) {
                    source.sendSystemMessage(Component.literal("§6[Warning] " + warnMsg));
                }
            } catch (Exception e) {
                String errorMsg = "Could not detect local IP and publicUrl is empty. Please configure 'publicUrl' in config/voicecontrol-server.toml.";
                AdminLogger.error(operator, errorMsg);
                if (source != null) source.sendFailure(Component.literal("§c" + errorMsg));
                return;
            }
        }

        boolean required = Config.SERVER.resourcePackRequired.get();
        Component prompt = Component.literal("Instalar sons customizados para o mod VoiceControl?");

        int count = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                player.connection.send(new ClientboundResourcePackPacket(url, hash, required, prompt));
                count++;
            } catch (Exception e) {
                AdminLogger.error(operator, "Failed to send resource pack packet to player " + player.getGameProfile().getName() + ": " + e.getMessage());
            }
        }

        String resultMsg = "Sent resource pack push request to " + count + " player(s). URL: " + url + " | Hash: " + hash;
        AdminLogger.info(operator, resultMsg);
        if (source != null) {
            source.sendSystemMessage(Component.literal("§a" + resultMsg));
        }
    }
}
