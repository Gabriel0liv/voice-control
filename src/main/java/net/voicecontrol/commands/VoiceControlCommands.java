package net.voicecontrol.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.voicecontrol.Config;
import net.voicecontrol.audio.AudioImportManager;
import net.voicecontrol.pack.ResourcePackBuilder;
import net.voicecontrol.pack.ResourcePackPusher;
import net.voicecontrol.pack.ResourcePackServer;
import net.voicecontrol.recording.MicRecordingSession;
import net.voicecontrol.recording.MonitorRecordingSession;
import net.voicecontrol.recording.RecordingManager;

import java.io.File;
import java.net.InetAddress;
import java.util.Collection;
import java.util.Map;

public class VoiceControlCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("voicectl")
                .requires(source -> source.hasPermission(Config.SERVER.commandsPermissionLevel.get()))
                .then(Commands.literal("rec")
                    .then(Commands.literal("mic")
                        .then(Commands.literal("start")
                            .then(Commands.literal("all")
                                .executes(context -> startMicAll(context.getSource()))
                            )
                            .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> startMicPlayer(context.getSource(), EntityArgument.getPlayer(context, "player")))
                            )
                        )
                        .then(Commands.literal("stop")
                            .then(Commands.literal("all")
                                .executes(context -> stopMicAll(context.getSource()))
                            )
                            .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> stopMicPlayer(context.getSource(), EntityArgument.getPlayer(context, "player")))
                            )
                        )
                    )
                    .then(Commands.literal("monitor")
                        .then(Commands.literal("start")
                            .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> startMonitorPlayer(context.getSource(), EntityArgument.getPlayer(context, "player")))
                            )
                        )
                        .then(Commands.literal("stop")
                            .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> stopMonitorPlayer(context.getSource(), EntityArgument.getPlayer(context, "player")))
                            )
                        )
                    )
                    .then(Commands.literal("status")
                        .executes(context -> getStatus(context.getSource()))
                    )
                )
                .then(Commands.literal("audio")
                    .then(Commands.literal("reload")
                        .executes(context -> reloadAudio(context.getSource()))
                    )
                    .then(Commands.literal("list")
                        .executes(context -> listAudio(context.getSource()))
                    )
                    .then(Commands.literal("info")
                        .then(Commands.argument("name", StringArgumentType.string())
                            .executes(context -> infoAudio(context.getSource(), StringArgumentType.getString(context, "name")))
                        )
                    )
                )
                .then(Commands.literal("pack")
                    .then(Commands.literal("build")
                        .executes(context -> buildPack(context.getSource()))
                    )
                    .then(Commands.literal("push")
                        .executes(context -> pushPack(context.getSource()))
                    )
                    .then(Commands.literal("status")
                        .executes(context -> packStatus(context.getSource()))
                    )
                )
        );
    }

    private static int startMicPlayer(CommandSourceStack source, ServerPlayer target) {
        String operator = source.getTextName();
        boolean success = RecordingManager.startMicRecording(target, operator);
        if (success) {
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Gravação do microfone de " + target.getGameProfile().getName() + " iniciada."), true);
        } else {
            source.sendFailure(Component.literal("§c[VoiceControl] Falha ao iniciar gravação (já gravando ou gravação desativada)."));
        }
        return 1;
    }

    private static int startMicAll(CommandSourceStack source) {
        String operator = source.getTextName();
        RecordingManager.startAllMicRecording(source.getServer(), operator);
        source.sendSuccess(() -> Component.literal("§a[VoiceControl] Gravação de microfone iniciada para todos os jogadores."), true);
        return 1;
    }

    private static int stopMicPlayer(CommandSourceStack source, ServerPlayer target) {
        boolean success = RecordingManager.stopMicRecording(target);
        if (success) {
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Gravação do microfone de " + target.getGameProfile().getName() + " interrompida."), true);
        } else {
            source.sendFailure(Component.literal("§c[VoiceControl] O jogador " + target.getGameProfile().getName() + " não estava sendo gravado."));
        }
        return 1;
    }

    private static int stopMicAll(CommandSourceStack source) {
        String operator = source.getTextName();
        RecordingManager.stopAllMicRecording(source.getServer(), operator);
        source.sendSuccess(() -> Component.literal("§a[VoiceControl] Gravação de microfone interrompida para todos os jogadores."), true);
        return 1;
    }

    private static int startMonitorPlayer(CommandSourceStack source, ServerPlayer target) {
        String operator = source.getTextName();
        boolean success = RecordingManager.startMonitorRecording(source.getServer(), target, operator);
        if (success) {
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Gravação monitorada para " + target.getGameProfile().getName() + " iniciada."), true);
        } else {
            source.sendFailure(Component.literal("§c[VoiceControl] Falha ao iniciar gravação monitorada (já monitorando ou gravação desativada)."));
        }
        return 1;
    }

    private static int stopMonitorPlayer(CommandSourceStack source, ServerPlayer target) {
        boolean success = RecordingManager.stopMonitorRecording(target);
        if (success) {
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Gravação monitorada para " + target.getGameProfile().getName() + " interrompida."), true);
        } else {
            source.sendFailure(Component.literal("§c[VoiceControl] O jogador " + target.getGameProfile().getName() + " não possui uma sessão monitorada ativa."));
        }
        return 1;
    }

    private static int getStatus(CommandSourceStack source) {
        Collection<MicRecordingSession> micSessions = RecordingManager.getActiveMicSessions();
        Collection<MonitorRecordingSession> monSessions = RecordingManager.getActiveMonitorSessions();

        source.sendSystemMessage(Component.literal("§6=== Status de Gravação VoiceControl ==="));
        source.sendSystemMessage(Component.literal("§eModo Gravar Todos (Mic): " + (RecordingManager.isAllMicActive() ? "§aAtivo" : "§cInativo")));
        source.sendSystemMessage(Component.literal("§eSessões Mic Ativas (" + micSessions.size() + "):"));
        for (MicRecordingSession s : micSessions) {
            source.sendSystemMessage(Component.literal("§a - " + s.getPlayerNick() + " §7(" + String.format("%.1f", s.getElapsedSeconds()) + "s)"));
        }
        source.sendSystemMessage(Component.literal("§eSessões Monitor Ativas (" + monSessions.size() + "):"));
        for (MonitorRecordingSession s : monSessions) {
            source.sendSystemMessage(Component.literal("§a - " + s.getMonitorNick() + " §7(" + String.format("%.1f", s.getElapsedSeconds()) + "s)"));
        }
        return 1;
    }

    private static int reloadAudio(CommandSourceStack source) {
        AudioImportManager.reloadAudios(source.getServer(), source);
        return 1;
    }

    private static int listAudio(CommandSourceStack source) {
        Map<String, File> sounds = AudioImportManager.getImportedSounds();
        String namespace = Config.SERVER.audioImportNamespace.get().toLowerCase();
        
        if (sounds.isEmpty()) {
            source.sendSystemMessage(Component.literal("§e[VoiceControl] Nenhum áudio importado disponível. Coloque arquivos em 'voice-control/imported-audios/' e execute '/voicectl audio reload'."));
        } else {
            source.sendSystemMessage(Component.literal("§6=== Áudios Importados Disponíveis (" + sounds.size() + ") ==="));
            for (String soundId : sounds.keySet()) {
                source.sendSystemMessage(Component.literal("§a - " + namespace + ":" + soundId));
            }
        }
        return 1;
    }

    private static int infoAudio(CommandSourceStack source, String name) {
        // Strip namespace if present
        String targetName = name;
        String namespace = Config.SERVER.audioImportNamespace.get().toLowerCase();
        if (name.startsWith(namespace + ":")) {
            targetName = name.substring(namespace.length() + 1);
        }

        Map<String, File> sounds = AudioImportManager.getImportedSounds();
        File file = sounds.get(targetName);
        if (file == null || !file.exists()) {
            source.sendFailure(Component.literal("§c[VoiceControl] Áudio '" + name + "' não encontrado. Certifique-se de que ele foi registrado via '/voicectl audio reload'."));
            return 1;
        }

        source.sendSystemMessage(Component.literal("§6=== Informações do Áudio: " + namespace + ":" + targetName + " ==="));
        source.sendSystemMessage(Component.literal("§eNome do arquivo: §a" + file.getName()));
        source.sendSystemMessage(Component.literal("§eCaminho físico: §a" + file.getAbsolutePath()));
        source.sendSystemMessage(Component.literal("§eTamanho: §a" + file.length() + " bytes"));
        
        // Calculate SHA-1 for the sounds
        String sha1 = ResourcePackBuilder.calculateSHA1(file);
        source.sendSystemMessage(Component.literal("§eSHA-1 do Arquivo: §a" + sha1));
        return 1;
    }

    private static int buildPack(CommandSourceStack source) {
        ResourcePackBuilder.buildPack(source.getServer(), source);
        return 1;
    }

    private static int pushPack(CommandSourceStack source) {
        ResourcePackPusher.pushPack(source.getServer(), source);
        return 1;
    }

    private static int packStatus(CommandSourceStack source) {
        boolean running = ResourcePackServer.isRunning();
        int port = Config.SERVER.resourcePackHttpPort.get();
        String url = Config.SERVER.resourcePackPublicUrl.get().trim();
        String hash = ResourcePackBuilder.getPackSha1();

        if (url.isEmpty()) {
            try {
                String ip = InetAddress.getLocalHost().getHostAddress();
                url = "http://" + ip + ":" + port + "/voicecontrol-pack.zip (Detecção automática)";
            } catch (Exception e) {
                url = "Desconhecida (Configuração 'publicUrl' necessária)";
            }
        }

        source.sendSystemMessage(Component.literal("§6=== Status do Resource Pack VoiceControl ==="));
        source.sendSystemMessage(Component.literal("§eServidor HTTP Interno: " + (running ? "§aExecutando" : "§cParado")));
        source.sendSystemMessage(Component.literal("§ePorta HTTP: §a" + port));
        source.sendSystemMessage(Component.literal("§eURL de Download: §a" + url));
        source.sendSystemMessage(Component.literal("§eSHA-1 do Pack: §a" + (hash.isEmpty() ? "§c(Nenhum pack compilado ainda)" : hash)));
        return 1;
    }
}
