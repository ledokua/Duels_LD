package net.ledok.duels_ld.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.ledok.duels_ld.manager.*;
import net.ledok.duels_ld.network.OpenAdminGuiPayload;
import net.ledok.duels_ld.network.SyncMatchmakingSettingsPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
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
            .then(Commands.literal("party")
                .then(Commands.literal("create")
                    .executes(DuelCommand::partyCreate))
                .then(Commands.literal("invite")
                    .then(Commands.argument("target", EntityArgument.player())
                        .executes(DuelCommand::partyInvite)))
                .then(Commands.literal("accept")
                    .then(Commands.argument("leader", EntityArgument.player())
                        .executes(DuelCommand::partyAccept)))
                .then(Commands.literal("leave")
                    .executes(DuelCommand::partyLeave))
                .then(Commands.literal("disband")
                    .executes(DuelCommand::partyDisband)))
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
                            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(DuelCommand::arenaAddSpawn)))))
                .then(Commands.literal("remspawn")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(DuelCommand::suggestArenaNames)
                        .then(Commands.argument("team", IntegerArgumentType.integer(1, 2))
                            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(DuelCommand::arenaRemoveSpawn)))))
                .then(Commands.literal("list")
                    .executes(DuelCommand::arenaList)))
            );
    }

    private static int stats(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        PlayerStats stats = StatsManager.getStats(player.getUUID());
        
        player.sendSystemMessage(Component.translatable("duels_ld.command.stats.header"));
        player.sendSystemMessage(Component.translatable("duels_ld.command.stats.wins", stats.getWins()));
        player.sendSystemMessage(Component.translatable("duels_ld.command.stats.losses", stats.getLosses()));
        player.sendSystemMessage(Component.translatable("duels_ld.command.stats.draws", stats.getDraws()));
        
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

        Map<UUID, Integer> ratings = is1v1 ? MMRManager.getAll1v1Ratings() : MMRManager.getAll2v2Ratings();
        if (ratings.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.leaderboard.none", mode), false);
            return 1;
        }

        List<Map.Entry<UUID, Integer>> sorted = new ArrayList<>(ratings.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.leaderboard.header", mode.toUpperCase()), false);
        int count = Math.min(10, sorted.size());
        for (int i = 0; i < count; i++) {
            Map.Entry<UUID, Integer> entry = sorted.get(i);
            String name = Component.translatable("duels_ld.command.player_unknown").getString();
            var profile = context.getSource().getServer().getProfileCache().get(entry.getKey());
            if (profile.isPresent()) {
                name = profile.get().getName();
            }
            final int rank = i + 1;
            final String playerName = name;
            final int rating = entry.getValue();
            context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.leaderboard.entry", rank, playerName, rating), false);
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
            config.weights.killBonus
        ));
        return 1;
    }

    private static int partyCreate(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        PartyManager.createParty(player);
        return 1;
    }

    private static int partyInvite(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer leader = context.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        PartyManager.invite(leader, target);
        return 1;
    }

    private static int partyAccept(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer target = context.getSource().getPlayerOrException();
        ServerPlayer leader = EntityArgument.getPlayer(context, "leader");
        PartyManager.acceptInvite(target, leader);
        return 1;
    }

    private static int partyLeave(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        PartyManager.leaveParty(player);
        return 1;
    }

    private static int partyDisband(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer leader = context.getSource().getPlayerOrException();
        if (!PartyManager.isLeader(leader.getUUID())) {
            context.getSource().sendFailure(Component.translatable("duels_ld.party.not_leader"));
            return 0;
        }
        PartyManager.disbandParty(leader);
        return 1;
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
        return 1;
    }

    private static int arenaAddSpawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        int team = IntegerArgumentType.getInteger(context, "team");
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
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
        boolean ok = ArenaManager.addSpawn(name, team, pos);
        if (ok) {
            context.getSource().sendSuccess(() -> Component.translatable("duels_ld.command.arena.spawn_added", name, team), true);
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
