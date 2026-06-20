package net.voicecontrol.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.voicecontrol.Config;
import net.voicecontrol.audio.AudioImportManager;
import net.voicecontrol.audio.VoiceChatPlaybackEngine;
import net.voicecontrol.network.VoiceControlNetwork;
import net.voicecontrol.network.packets.DynamicSoundPlayPacket;
import net.voicecontrol.recording.MicRecordingSession;
import net.voicecontrol.recording.MonitorRecordingSession;
import net.voicecontrol.recording.RecordingManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class VoiceControlCommands {

    public static final SuggestionProvider<CommandSourceStack> SUGGEST_SOUNDS = (context, builder) -> {
        List<String> list = new ArrayList<>();
        for (String key : AudioImportManager.getImportedSounds().keySet()) {
            list.add(key); // voicecontrol:teste1
            if (key.startsWith("voicecontrol:")) {
                list.add(key.substring("voicecontrol:".length())); // teste1
            }
        }
        return SharedSuggestionProvider.suggest(list, builder);
    };

    public static final SuggestionProvider<CommandSourceStack> SUGGEST_SOURCES = (context, builder) -> {
        return SharedSuggestionProvider.suggest(List.of("master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient", "voice"), builder);
    };

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
                        .then(Commands.argument("sound", ResourceLocationArgument.id())
                            .suggests(SUGGEST_SOUNDS)
                            .executes(context -> infoAudio(context.getSource(), ResourceLocationArgument.getId(context, "sound")))
                        )
                    )
                    .then(Commands.literal("sync")
                        .then(Commands.literal("all")
                            .executes(context -> syncAudioAll(context.getSource()))
                        )
                        .then(Commands.argument("player", EntityArgument.players())
                            .executes(context -> syncAudioPlayer(context.getSource(), EntityArgument.getPlayers(context, "player")))
                        )
                    )
                )
                .then(Commands.literal("playsound")
                    .then(Commands.argument("sound", ResourceLocationArgument.id()).suggests(SUGGEST_SOUNDS)
                        .then(Commands.argument("source", StringArgumentType.word()).suggests(SUGGEST_SOURCES)
                            .then(Commands.argument("targets", EntityArgument.players())
                                .executes(context -> playSound(context.getSource(), ResourceLocationArgument.getId(context, "sound"), StringArgumentType.getString(context, "source"), EntityArgument.getPlayers(context, "targets"), null, null, null, null))
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                    .executes(context -> playSound(context.getSource(), ResourceLocationArgument.getId(context, "sound"), StringArgumentType.getString(context, "source"), EntityArgument.getPlayers(context, "targets"), Vec3Argument.getVec3(context, "pos"), null, null, null))
                                    .then(Commands.argument("volume", FloatArgumentType.floatArg(0.0f))
                                        .executes(context -> playSound(context.getSource(), ResourceLocationArgument.getId(context, "sound"), StringArgumentType.getString(context, "source"), EntityArgument.getPlayers(context, "targets"), Vec3Argument.getVec3(context, "pos"), FloatArgumentType.getFloat(context, "volume"), null, null))
                                        .then(Commands.argument("pitch", FloatArgumentType.floatArg(0.5f, 2.0f))
                                            .executes(context -> playSound(context.getSource(), ResourceLocationArgument.getId(context, "sound"), StringArgumentType.getString(context, "source"), EntityArgument.getPlayers(context, "targets"), Vec3Argument.getVec3(context, "pos"), FloatArgumentType.getFloat(context, "volume"), FloatArgumentType.getFloat(context, "pitch"), null))
                                            .then(Commands.argument("minVolume", FloatArgumentType.floatArg(0.0f, 1.0f))
                                                .executes(context -> playSound(context.getSource(), ResourceLocationArgument.getId(context, "sound"), StringArgumentType.getString(context, "source"), EntityArgument.getPlayers(context, "targets"), Vec3Argument.getVec3(context, "pos"), FloatArgumentType.getFloat(context, "volume"), FloatArgumentType.getFloat(context, "pitch"), FloatArgumentType.getFloat(context, "minVolume")))
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
                .then(Commands.literal("voiceplay")
                    .then(Commands.literal("stop")
                        .then(Commands.argument("sound", ResourceLocationArgument.id()).suggests(SUGGEST_SOUNDS)
                            .executes(context -> stopVoicePlay(context.getSource(), ResourceLocationArgument.getId(context, "sound")))
                        )
                    )
                    .then(Commands.argument("sound", ResourceLocationArgument.id()).suggests(SUGGEST_SOUNDS)
                        .then(Commands.argument("targets", EntityArgument.players())
                            .executes(context -> playVoiceStatic(context.getSource(), ResourceLocationArgument.getId(context, "sound"), EntityArgument.getPlayers(context, "targets")))
                        )
                        .then(Commands.literal("at")
                            .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(context -> playVoiceLocational(context.getSource(), ResourceLocationArgument.getId(context, "sound"), Vec3Argument.getVec3(context, "pos")))
                            )
                        )
                        .then(Commands.literal("from")
                            .then(Commands.argument("entity", EntityArgument.entity())
                                .executes(context -> playVoiceEntity(context.getSource(), ResourceLocationArgument.getId(context, "sound"), EntityArgument.getEntity(context, "entity")))
                            )
                        )
                    )
                )
                .then(Commands.literal("stopsound")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes(context -> stopSoundCommand(context.getSource(), EntityArgument.getPlayers(context, "targets"), null, null))
                        .then(Commands.argument("source", StringArgumentType.word()).suggests(SUGGEST_SOURCES)
                            .executes(context -> stopSoundCommand(context.getSource(), EntityArgument.getPlayers(context, "targets"), StringArgumentType.getString(context, "source"), null))
                            .then(Commands.argument("sound", ResourceLocationArgument.id()).suggests(SUGGEST_SOUNDS)
                                .executes(context -> stopSoundCommand(context.getSource(), EntityArgument.getPlayers(context, "targets"), StringArgumentType.getString(context, "source"), ResourceLocationArgument.getId(context, "sound")))
                            )
                        )
                    )
                )
                .then(Commands.literal("voicestop")
                    .then(Commands.literal("all")
                        .executes(context -> voiceStopAll(context.getSource()))
                    )
                    .then(Commands.argument("sound", ResourceLocationArgument.id()).suggests(SUGGEST_SOUNDS)
                        .executes(context -> voiceStopSound(context.getSource(), ResourceLocationArgument.getId(context, "sound")))
                    )
                )
        );

    }

    private static String resolveSoundId(ResourceLocation location) {
        String id = location.toString();
        if (AudioImportManager.getImportedSounds().containsKey(id)) {
            return id;
        }
        if ("minecraft".equals(location.getNamespace())) {
            String fallbackId = "voicecontrol:" + location.getPath();
            if (AudioImportManager.getImportedSounds().containsKey(fallbackId)) {
                return fallbackId;
            }
        }
        return null;
    }

    private static int startMicPlayer(CommandSourceStack source, ServerPlayer target) {
        String operator = source.getTextName();
        boolean success = RecordingManager.startMicRecording(target, operator);
        if (success) {
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Gravação do microfone de " + target.getGameProfile().getName() + " iniciada."), false);
        } else {
            source.sendFailure(Component.literal("§c[VoiceControl] Falha ao iniciar gravação (já gravando ou gravação desativada)."));
        }
        return 1;
    }

    private static int startMicAll(CommandSourceStack source) {
        String operator = source.getTextName();
        RecordingManager.startAllMicRecording(source.getServer(), operator);
        source.sendSuccess(() -> Component.literal("§a[VoiceControl] Gravação de microfone iniciada para todos os jogadores."), false);
        return 1;
    }

    private static int stopMicPlayer(CommandSourceStack source, ServerPlayer target) {
        boolean success = RecordingManager.stopMicRecording(target);
        if (success) {
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Gravação do microfone de " + target.getGameProfile().getName() + " interrompida."), false);
        } else {
            source.sendFailure(Component.literal("§c[VoiceControl] O jogador " + target.getGameProfile().getName() + " não estava sendo gravado."));
        }
        return 1;
    }

    private static int stopMicAll(CommandSourceStack source) {
        String operator = source.getTextName();
        RecordingManager.stopAllMicRecording(source.getServer(), operator);
        source.sendSuccess(() -> Component.literal("§a[VoiceControl] Gravação de microfone interrompida para todos os jogadores."), false);
        return 1;
    }

    private static int startMonitorPlayer(CommandSourceStack source, ServerPlayer target) {
        String operator = source.getTextName();
        boolean success = RecordingManager.startMonitorRecording(source.getServer(), target, operator);
        if (success) {
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Gravação monitorada para " + target.getGameProfile().getName() + " iniciada."), false);
        } else {
            source.sendFailure(Component.literal("§c[VoiceControl] Falha ao iniciar gravação monitorada (já monitorando ou gravação desativada)."));
        }
        return 1;
    }

    private static int stopMonitorPlayer(CommandSourceStack source, ServerPlayer target) {
        boolean success = RecordingManager.stopMonitorRecording(target);
        if (success) {
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Gravação monitorada para " + target.getGameProfile().getName() + " interrompida."), false);
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
        Map<String, AudioImportManager.AudioEntry> sounds = AudioImportManager.getImportedSounds();
        
        if (sounds.isEmpty()) {
            source.sendSystemMessage(Component.literal("§e[VoiceControl] Nenhum áudio importado disponível. Coloque arquivos em 'voice-control/imported-audios/' e execute '/voicectl audio reload'."));
        } else {
            source.sendSystemMessage(Component.literal("§6=== Áudios Importados Disponíveis (" + sounds.size() + ") ==="));
            for (String soundId : sounds.keySet()) {
                source.sendSystemMessage(Component.literal("§a - " + soundId));
            }
        }
        return 1;
    }

    private static int infoAudio(CommandSourceStack source, ResourceLocation soundLoc) {
        String soundId = resolveSoundId(soundLoc);
        if (soundId == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Áudio '" + soundLoc + "' não encontrado na biblioteca."));
            return 1;
        }

        AudioImportManager.AudioEntry entry = AudioImportManager.getImportedSounds().get(soundId);
        if (entry == null || !entry.file.exists()) {
            source.sendFailure(Component.literal("§c[VoiceControl] Arquivo físico para '" + soundId + "' não encontrado."));
            return 1;
        }

        source.sendSystemMessage(Component.literal("§6=== Informações do Áudio: " + soundId + " ==="));
        source.sendSystemMessage(Component.literal("§eNome do arquivo: §a" + entry.file.getName()));
        source.sendSystemMessage(Component.literal("§eCaminho físico: §a" + entry.file.getAbsolutePath()));
        source.sendSystemMessage(Component.literal("§eTamanho: §a" + entry.sizeBytes + " bytes"));
        source.sendSystemMessage(Component.literal("§eSHA-256 do Arquivo: §a" + entry.sha256));
        return 1;
    }

    private static int syncAudioAll(CommandSourceStack source) {
        int sent = 0;
        int skipped = 0;
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            if (AudioImportManager.isPlayerReady(player)) {
                AudioImportManager.sendManifestIfReady(player);
                sent++;
            } else {
                skipped++;
            }
        }
        final int finalSent = sent;
        final int finalSkipped = skipped;
        source.sendSuccess(() -> Component.literal("§a[VoiceControl] Manifest enviado para " + finalSent + " jogador(es). " + finalSkipped + " jogador(es) ignorados porque não têm VoiceControl client pronto."), false);
        return 1;
    }

    private static int syncAudioPlayer(CommandSourceStack source, Collection<ServerPlayer> targets) {
        int sent = 0;
        for (ServerPlayer player : targets) {
            if (AudioImportManager.isPlayerReady(player)) {
                AudioImportManager.sendManifestIfReady(player);
                sent++;
            } else {
                source.sendSystemMessage(Component.literal("§c[VoiceControl] " + player.getGameProfile().getName() + " não está pronto (não possui o client companion ativo)."));
            }
        }
        final int finalSent = sent;
        if (finalSent > 0) {
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Enviado manifest para sincronização com " + finalSent + " jogador(es)."), false);
        } else {
            source.sendSuccess(() -> Component.literal("§c[VoiceControl] Nenhum jogador recebeu manifest. Todos os alvos estavam sem VoiceControl client pronto."), false);
        }
        return 1;
    }

    private static int playSound(CommandSourceStack source, ResourceLocation soundLoc, String categoryName, Collection<ServerPlayer> targets, Vec3 pos, Float volume, Float pitch, Float minVolume) {
        String soundId = resolveSoundId(soundLoc);
        if (soundId == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Som '" + soundLoc + "' não encontrado na biblioteca."));
            return 1;
        }

        SoundSource category = SoundSource.MASTER;
        try {
            category = SoundSource.valueOf(categoryName.toUpperCase());
        } catch (IllegalArgumentException ignored) {}

        boolean positional = (pos != null);
        double x = positional ? pos.x : 0.0;
        double y = positional ? pos.y : 0.0;
        double z = positional ? pos.z : 0.0;

        float vol = volume != null ? volume : 1.0f;
        float pit = pitch != null ? pitch : 1.0f;
        float minVol = minVolume != null ? minVolume : 0.0f;

        List<ServerPlayer> readyTargets = new ArrayList<>();
        for (ServerPlayer target : targets) {
            if (AudioImportManager.isPlayerReady(target)) {
                boolean cached = AudioImportManager.isSoundCachedForPlayer(target, soundId);
                boolean playAfterDownload = Config.SERVER.dynamicSoundPlayAfterDownloadIfMissing.get();
                if (cached) {
                    readyTargets.add(target);
                } else {
                    if (playAfterDownload) {
                        readyTargets.add(target);
                        source.sendSystemMessage(Component.literal("§e[VoiceControl] Aviso: " + target.getGameProfile().getName() + " não tem o som '" + soundId + "' em cache. O cliente vai baixar e tocar assim que terminar."));
                    } else {
                        source.sendSystemMessage(Component.literal("§c[VoiceControl] " + target.getGameProfile().getName() + " não tem '" + soundId + "' em cache. Execute /voicectl audio sync " + target.getGameProfile().getName() + "."));
                    }
                }
            } else {
                source.sendSystemMessage(Component.literal("§c[VoiceControl] O jogador " + target.getGameProfile().getName() + " não possui o client-companion pronto."));
            }
        }

        if (!readyTargets.isEmpty()) {
            DynamicSoundPlayPacket packet = new DynamicSoundPlayPacket(soundId, category.getName(), positional, x, y, z, vol, pit, minVol, 1.0f);
            for (ServerPlayer target : readyTargets) {
                VoiceControlNetwork.sendToClient(packet, target);
            }
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Comando de reprodução de som '" + soundId + "' enviado para " + readyTargets.size() + " jogador(es)."), false);
        }
        return 1;
    }

    private static int playVoiceStatic(CommandSourceStack source, ResourceLocation soundLoc, Collection<ServerPlayer> targets) {
        String soundId = resolveSoundId(soundLoc);
        if (soundId == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Som '" + soundLoc + "' não encontrado na biblioteca."));
            return 1;
        }

        List<ServerPlayer> targetsList = new ArrayList<>(targets);
        boolean success = VoiceChatPlaybackEngine.playStatic(soundId, targetsList, source);
        if (success) {
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Iniciada reprodução via Voice Chat (estático) de '" + soundId + "' para " + targetsList.size() + " jogador(es)."), false);
        }
        return 1;
    }

    private static int playVoiceLocational(CommandSourceStack source, ResourceLocation soundLoc, Vec3 pos) {
        String soundId = resolveSoundId(soundLoc);
        if (soundId == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Som '" + soundLoc + "' não encontrado na biblioteca."));
            return 1;
        }

        ServerLevel level = source.getLevel();
        boolean success = VoiceChatPlaybackEngine.playLocational(soundId, level, pos.x, pos.y, pos.z, source);
        if (success) {
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Iniciada reprodução via Voice Chat (posicional) de '" + soundId + "' em " + String.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z) + "."), false);
        }
        return 1;
    }

    private static int playVoiceEntity(CommandSourceStack source, ResourceLocation soundLoc, Entity entity) {
        String soundId = resolveSoundId(soundLoc);
        if (soundId == null) {
            source.sendFailure(Component.literal("§c[VoiceControl] Som '" + soundLoc + "' não encontrado na biblioteca."));
            return 1;
        }

        boolean success = VoiceChatPlaybackEngine.playEntity(soundId, entity, source);
        if (success) {
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Iniciada reprodução via Voice Chat (entidade) de '" + soundId + "' a partir de " + entity.getDisplayName().getString() + "."), false);
        }
        return 1;
    }



    private static int stopVoicePlay(CommandSourceStack source, ResourceLocation soundLoc) {
        return voiceStopSound(source, soundLoc);
    }

    private static int stopSoundCommand(CommandSourceStack source, Collection<ServerPlayer> targets, String sourceCategory, ResourceLocation soundLoc) {
        String soundId = null;
        if (soundLoc != null) {
            soundId = resolveSoundId(soundLoc);
            if (soundId == null) {
                soundId = soundLoc.toString();
            }
        }

        String finalCategory = null;
        if (sourceCategory != null) {
            SoundSource category = SoundSource.MASTER;
            try {
                category = SoundSource.valueOf(sourceCategory.toUpperCase());
                finalCategory = category.getName();
            } catch (IllegalArgumentException ignored) {
                finalCategory = sourceCategory;
            }
        }

        int sent = 0;
        for (ServerPlayer target : targets) {
            if (AudioImportManager.isPlayerReady(target)) {
                net.voicecontrol.network.packets.DynamicSoundStopPacket packet = new net.voicecontrol.network.packets.DynamicSoundStopPacket(soundId, finalCategory);
                VoiceControlNetwork.sendToClient(packet, target);
                sent++;
            } else {
                source.sendSystemMessage(Component.literal("§c[VoiceControl] O jogador " + target.getGameProfile().getName() + " não possui o client companion pronto. Stopsound não enviado."));
            }
        }

        final int finalSent = sent;
        if (finalSent > 0) {
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Stopsound enviado para " + finalSent + " jogador(es)."), false);
        } else {
            source.sendSuccess(() -> Component.literal("§c[VoiceControl] Nenhum jogador recebeu o stopsound."), false);
        }

        return 1;
    }

    private static int voiceStopSound(CommandSourceStack source, ResourceLocation soundLoc) {
        String soundId = resolveSoundId(soundLoc);
        if (soundId == null) {
            soundId = soundLoc.toString();
        }

        boolean stopped = VoiceChatPlaybackEngine.stopSound(soundId);
        final String finalId = soundId;
        if (stopped) {
            source.sendSuccess(() -> Component.literal("§a[VoiceControl] Reprodução via Voice Chat de '" + finalId + "' interrompida."), false);
        } else {
            source.sendFailure(Component.literal("§c[VoiceControl] Nenhuma reprodução ativa encontrada para '" + finalId + "'."));
        }
        return 1;
    }

    private static int voiceStopAll(CommandSourceStack source) {
        VoiceChatPlaybackEngine.stopAll();
        source.sendSuccess(() -> Component.literal("§a[VoiceControl] Todas as reproduções via Voice Chat foram interrompidas."), false);
        return 1;
    }
}
