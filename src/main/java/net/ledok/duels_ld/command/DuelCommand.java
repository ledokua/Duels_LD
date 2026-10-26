package net.ledok.duels_ld.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.ledok.duels_ld.api.DuelsApi;
import net.ledok.duels_ld.manager.*;
import net.ledok.duels_ld.network.OpenAdminGuiPayload;
import net.ledok.duels_ld.network.SyncMatchmakingSettingsPayload;
import net.ledok.duels_ld.manager.ArenaWand;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class DuelCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("duel")
            .then(Commands.literal("stats")
                .executes(DuelCommand::stats))
            .then(Commands.literal("leaderboard")
                .then(Commands.argument("mode", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        builder.suggest("1v1");
                        builder.suggest("2v2");
                        return builder.buildFuture();
                    })
                    .executes(DuelCommand::leaderboard)))
            .then(Commands.literal("queue")
                .then(Commands.argument("mode", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        builder.suggest("1v1");
                        builder.suggest("2v2");
                        return builder.buildFuture();
                    })
                    .executes(DuelCommand::queue)))
            .then(Commands.literal("leavequeue")
                .executes(DuelCommand::leaveQueue))
            .then(Commands.literal("admingui")
                .requires(source -> source.hasPermission(2))
                .executes(DuelCommand::openAdminGui))
            .then(Commands.literal("reload")
                .requires(source -> source.hasPermission(2))
                .executes(DuelCommand::reloadConfig))
            .then(Commands.literal("arena")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("create")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(DuelCommand::arenaCreate)))
                .then(Commands.literal("remove")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(DuelCommand::suggestArenaNames)
                        .executes(DuelCommand::arenaRemove)))
                .then(Commands.literal("pos1")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(DuelCommand::suggestArenaNames)
                        .executes(DuelCommand::arenaPos1)))
                .then(Commands.literal("pos2")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(DuelCommand::suggestArenaNames)
                        .executes(DuelCommand::arenaPos2)))
                .then(Commands.literal("addspawn")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(DuelCommand::suggestArenaNames)
                        .then(Commands.argument("team", IntegerArgumentType.integer(1, 2))
                            .executes(DuelCommand::arenaAddSpawnHere)
                            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(DuelCommand::arenaAddSpawn)))))
                .then(Commands.literal("remspawn")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(DuelCommand::suggestArenaNames)
                        .then(Commands.argument("team", IntegerArgumentType.integer(1, 2))
                            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(DuelCommand::arenaRemoveSpawn)))))
                .then(Commands.literal("list")
                    .executes(DuelCommand::arenaList))
                .then(Commands.literal("info")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(DuelCommand::suggestArenaNames)
                        .executes(DuelCommand::arenaInfo)))
                .then(Commands.literal("wand")
                    .executes(DuelCommand::arenaWandUnbound)
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(DuelCommand::suggestArenaNames)
                        .executes(DuelCommand::arenaWand)))
                .then(Commands.literal("gui")
                    .executes(DuelCommand::arenaGui)))
            );
    }

    private static int stats(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        player.sendSystemMessage(Component.translatable("duels_ld.command.stats.header"));
        Optional<DuelsApi.Stats> stats = DuelsApi.stats(player.server, player.getUUID());
        if (stats.isEmpty()) {
            player.sendSystemMessage(Component.translatable("duels_ld.command.stats.none"));
            return 1;
        }
        for (DuelsApi.Mode mode : DuelsApi.Mode.values()) {
            DuelsApi.ModeStats m = stats.get().of(mode);
            int rank = DuelsApi.rank(player.server, mode, player.getUUID());
            Component rankText = rank > 0
                ? Component.translatable("duels_ld.command.stats.rank", rank)
                : Component.translatable("duels_ld.command.stats.unranked");
            player.sendSystemMessage(Component.translatable("duels_ld.command.stats.mode",
                mode == DuelsApi.Mode.ONE_V_ONE ? "1v1" : "2v2", m.rating(), rankText, m.wins(), m.losses(), m.draws()));
        }
        PlayerStats totals = StatsManager.find(player.getUUID());
        if (totals != null) {
            player.sendSystemMessage(Component.translatable("duels_ld.command.stats.total",
                totals.getWins(), totals.getLosses(), totals.getDraws()));
        }
        return 1;
    }

    private static int leaderboard(CommandContext<CommandSourceStack> context) {
        String mode = StringArgumentType.getString(context, "mode");
        boolean is1v1 = "1v1".equalsIgnoreCase(mode);
        boolean is2v2 = "2v2".equalsIgnoreCase(mode);
        if (!is1v1 && !is2v2) {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.unknown_mode"));
            return 0;
        }

        List<DuelsApi.LeaderboardEntry> top = DuelsApi.leaderboard(context.getSource().getServer(),
            is1v1 ? DuelsApi.Mode.ONE_V_ONE : DuelsApi.Mode.TWO_V_TWO, 10);
        if (top.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.leaderboard.none", mode), false);
            return 1;
        }

        context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.leaderboard.header", mode.toUpperCase()), false);
        for (int i = 0; i < top.size(); i++) {
            DuelsApi.LeaderboardEntry entry = top.get(i);
            final int rank = i + 1;
            context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.leaderboard.entry", rank, entry.name(), entry.rating()), false);
        }
        return 1;
    }
    
    private static int queue(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String mode = StringArgumentType.getString(context, "mode");
        if ("1v1".equalsIgnoreCase(mode)) {
            MatchmakingManager.joinQueue(player, net.ledok.duels_ld.network.JoinQueuePayload.MODE_1V1);
            return 1;
        }
        if ("2v2".equalsIgnoreCase(mode)) {
            MatchmakingManager.joinQueue(player, net.ledok.duels_ld.network.JoinQueuePayload.MODE_2V2);
            return 1;
        }
        context.getSource().sendFailure(Component.translatable("duels_ld.command.unknown_mode"));
        return 0;
    }

    private static int leaveQueue(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MatchmakingManager.leaveAllQueues(player);
        return 1;
    }

    private static int openAdminGui(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerPlayNetworking.send(player, new OpenAdminGuiPayload());

        MatchmakingConfigManager.MatchmakingConfig config = MatchmakingConfigManager.getConfig();
        ServerPlayNetworking.send(player, new SyncMatchmakingSettingsPayload(
            config.oneVOne.durationSeconds,
            config.oneVOne.winHpPercentage,
            config.twoVTwo.durationSeconds,
            config.twoVTwo.winHpPercentage,
            config.weights.offensePerDamage,
            config.weights.supportPerHeal,
            config.weights.defensePerBlocked,
            config.weights.killBonus,
            config.battle.outOfBoundsDamagePercent,
            config.battle.outOfBoundsPointPenaltyPercent,
            config.battle.extraHealth
        ));
        return 1;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        boolean ok = MatchmakingConfigManager.reload();
        net.ledok.duels_ld.wager.WagerConfig.load();
        if (ok) {
            context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.config_reloaded").withStyle(ChatFormatting.GREEN), true);
        } else {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.config_reload_failed"));
        }
        return ok ? 1 : 0;
    }

    private static int arenaCreate(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        ServerPlayer player = context.getSource().getPlayerOrException();
        boolean created = ArenaManager.createArena(name, player.level().dimension());
        if (created) {
            context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.arena.created", name), true);
        } else {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.arena.exists", name));
        }
        return 1;
    }

    private static int arenaRemove(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        if (ArenaManager.isActive(name)) {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.arena.in_use", name));
            return 0;
        }
        boolean removed = ArenaManager.removeArena(name);
        if (removed) {
            context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.arena.removed", name), true);
        } else {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.arena.not_found", name));
        }
        return 1;
    }

    private static int arenaPos1(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        ServerPlayer player = context.getSource().getPlayerOrException();
        ArenaManager.Arena arena = ArenaManager.getArena(name);
        if (arena == null) {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.arena.not_found", name));
            return 0;
        }
        if (!arena.dimensionId.equals(player.level().dimension().location().toString())) {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.arena.wrong_dimension_pos1"));
            return 0;
        }
        ArenaManager.setPos1(name, player.blockPosition());
        context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.arena.pos1_set", name), true);
        refreshOverlay(context.getSource(), name);
        return 1;
    }

    private static int arenaPos2(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        ServerPlayer player = context.getSource().getPlayerOrException();
        ArenaManager.Arena arena = ArenaManager.getArena(name);
        if (arena == null) {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.arena.not_found", name));
            return 0;
        }
        if (!arena.dimensionId.equals(player.level().dimension().location().toString())) {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.arena.wrong_dimension_pos2"));
            return 0;
        }
        ArenaManager.setPos2(name, player.blockPosition());
        context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.arena.pos2_set", name), true);
        refreshOverlay(context.getSource(), name);
        return 1;
    }

    private static int arenaAddSpawnHere(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        return doAddSpawn(context, player.blockPosition());
    }

    private static int arenaAddSpawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return doAddSpawn(context, BlockPosArgument.getLoadedBlockPos(context, "pos"));
    }

    private static int doAddSpawn(CommandContext<CommandSourceStack> context, BlockPos pos) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        int team = IntegerArgumentType.getInteger(context, "team");
        ServerPlayer player = context.getSource().getPlayerOrException();
        ArenaManager.Arena arena = ArenaManager.getArena(name);
        if (arena == null) {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.arena.not_found", name));
            return 0;
        }
        if (!arena.dimensionId.equals(player.level().dimension().location().toString())) {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.arena.wrong_dimension_spawn"));
            return 0;
        }
        // Capture the admin's facing so duelers spawn looking the way the admin was looking.
        boolean ok = ArenaManager.addSpawn(name, team, pos, player.getYRot(), player.getXRot());
        if (ok) {
            context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.arena.spawn_added", name, team), true);
            refreshOverlay(context.getSource(), name);
        } else {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.arena.spawn_add_failed"));
        }
        return 1;
    }

    private static int arenaRemoveSpawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        int team = IntegerArgumentType.getInteger(context, "team");
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        ArenaManager.Arena arena = ArenaManager.getArena(name);
        if (arena == null) {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.arena.not_found", name));
            return 0;
        }
        boolean ok = ArenaManager.removeSpawn(name, team, pos);
        if (ok) {
            context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.arena.spawn_removed", name, team), true);
            refreshOverlay(context.getSource(), name);
        } else {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.arena.spawn_not_found"));
        }
        return 1;
    }

    private static int arenaList(CommandContext<CommandSourceStack> context) {
        List<ArenaManager.Arena> list = ArenaManager.listArenas();
        if (list.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.arena.none"), false);
            return 1;
        }
        context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.arena.list_header"), false);
        for (ArenaManager.Arena arena : list) {
            context.getSource().sendSuccess(() -> Component.translatable(
                "duels_ld.command.arena.list_entry",
                arena.name,
                arena.team1Spawns.size(),
                arena.team2Spawns.size()
            ), false);
        }
        return 1;
    }

    private static int arenaInfo(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        ArenaManager.Arena arena = ArenaManager.getArena(name);
        if (arena == null) {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.arena.not_found", name));
            return 0;
        }
        CommandSourceStack src = context.getSource();
        src.sendSuccess(() -> Component.translatable("duels_ld.command.arena.info_header", name).withStyle(ChatFormatting.AQUA), false);
        src.sendSuccess(() -> Component.translatable("duels_ld.command.arena.info_line_dim", arena.dimensionId), false);
        src.sendSuccess(() -> Component.translatable("duels_ld.command.arena.info_line_bounds", yn(ArenaManager.hasBounds(arena))), false);
        src.sendSuccess(() -> Component.translatable("duels_ld.command.arena.info_line_spawns", arena.team1Spawns.size(), arena.team2Spawns.size()), false);
        src.sendSuccess(() -> Component.translatable("duels_ld.command.arena.info_line_ready", yn(ArenaManager.isReadyFor(arena, 1)), yn(ArenaManager.isReadyFor(arena, 2))), false);
        src.sendSuccess(() -> Component.translatable("duels_ld.command.arena.info_line_active", yn(ArenaManager.isActive(name))), false);
        int outside = ArenaManager.spawnsOutsideBounds(arena);
        if (outside > 0) {
            src.sendSuccess(() -> Component.translatable("duels_ld.command.arena.info_warn_outside", outside).withStyle(ChatFormatting.GOLD), false);
        }
        return 1;
    }

    private static int arenaWand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        ServerPlayer player = context.getSource().getPlayerOrException();
        ArenaManager.Arena arena = ArenaManager.getArena(name);
        if (arena == null) {
            context.getSource().sendFailure(Component.translatable("duels_ld.command.arena.not_found", name));
            return 0;
        }
        ArenaWand.give(player, name);
        ArenaWand.sendOverlay(player, name);
        context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.arena.wand_given", name).withStyle(ChatFormatting.LIGHT_PURPLE), false);
        return 1;
    }

    private static int arenaWandUnbound(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ArenaWand.giveUnbound(player);
        context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.arena.wand_given_unbound").withStyle(ChatFormatting.LIGHT_PURPLE), false);
        return 1;
    }

    private static int arenaGui(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        net.ledok.duels_ld.manager.ArenaGuiHandler.sendArenaList(player, net.ledok.duels_ld.network.ArenaListPayload.OPEN_GUI);
        return 1;
    }

    /** Pushes a fresh overlay snapshot to the editing player so the wand visualization stays current. */
    private static void refreshOverlay(CommandSourceStack source, String name) {
        if (source.getEntity() instanceof ServerPlayer player) {
            ArenaWand.sendOverlay(player, name);
        }
    }

    private static Component yn(boolean value) {
        return value
            ? Component.literal("✓").withStyle(ChatFormatting.GREEN)
            : Component.literal("✗").withStyle(ChatFormatting.RED);
    }

    private static CompletableFuture<Suggestions> suggestArenaNames(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        for (ArenaManager.Arena arena : ArenaManager.listArenas()) {
            if (arena.name.toLowerCase().startsWith(remaining)) {
                builder.suggest(arena.name);
            }
        }
        return builder.buildFuture();
    }
}
