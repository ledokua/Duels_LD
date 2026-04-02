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
            .then(Commands.literal("invite")
                .then(Commands.argument("target", EntityArgument.player())
                    .executes(DuelCommand::invite)
                    .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests(DuelCommand::suggestArgs)
                        .executes(DuelCommand::inviteWithArgs))))
            .then(Commands.literal("accept")
                .executes(DuelCommand::accept))
            .then(Commands.literal("decline")
                .then(Commands.argument("target", EntityArgument.player())
                    .executes(DuelCommand::decline)))
            .then(Commands.literal("stats")
                .executes(DuelCommand::stats))
            .then(Commands.literal("battle")
                .requires(source -> source.hasPermission(2) || (source.isPlayer() && ConfigManager.isPlayerAuthorized(source.getPlayer().getName().getString())))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                    .suggests(DuelCommand::suggestBattleArgs)
                    .executes(DuelCommand::startBattle)))
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
            .then(Commands.literal("reload")
                .requires(source -> source.hasPermission(2))
                .executes(DuelCommand::reloadConfig)));
    }

    private static CompletableFuture<Suggestions> suggestArgs(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        if (!remaining.contains("time:")) {
            builder.suggest(remaining + "time:60s");
            builder.suggest(remaining + "time:2m");
        }
        if (!remaining.contains("winhp:")) {
            builder.suggest(remaining + "winhp:50%");
            builder.suggest(remaining + "winhp:10%");
        }
        return builder.buildFuture();
    }
    
    private static CompletableFuture<Suggestions> suggestBattleArgs(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        String[] parts = remaining.split(" ");
        String lastPart = parts.length > 0 ? parts[parts.length - 1] : "";

        // Determine the prefix to keep
        String prefix = "";
        if (remaining.lastIndexOf(' ') != -1) {
            prefix = remaining.substring(0, remaining.lastIndexOf(' ') + 1);
        }

        // Determine context: are we adding players to a team?
        boolean addingPlayers = false;
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].endsWith(":")) {
                addingPlayers = true;
                break;
            }
            if (parts[i].equalsIgnoreCase("time:")) {
                break;
            }
        }

        if (remaining.endsWith(" ")) {
            // After a space, suggest new tokens
            prefix = remaining;
            lastPart = "";
            
            // Suggest players if we are in a team block
            if (addingPlayers) {
                for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
                    builder.suggest(prefix + player.getName().getString());
                }
            }
            // Always suggest new team and time
            int teamCount = 0;
            for (String p : parts) {
                if (p.endsWith(":")) teamCount++;
            }
            builder.suggest(prefix + "team" + (teamCount + 1) + ":");
            if (!remaining.contains("time:")) {
                builder.suggest(prefix + "time:");
            }

        } else {
            // Completing a token
            if (addingPlayers) {
                // Suggest players
                for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
                    if (player.getName().getString().toLowerCase().startsWith(lastPart.toLowerCase())) {
                        builder.suggest(prefix + player.getName().getString());
                    }
                }
            }
            
            // Suggest new team or time
            int teamCount = 0;
            for (String p : parts) {
                if (p.endsWith(":")) teamCount++;
            }
            String nextTeam = "team" + (teamCount + 1) + ":";
            if (nextTeam.startsWith(lastPart.toLowerCase())) {
                builder.suggest(prefix + nextTeam);
            }
            if ("time:".startsWith(lastPart.toLowerCase()) && !remaining.contains("time:")) {
                builder.suggest(prefix + "time:");
            }
        }

        return builder.buildFuture();
    }

    private static int invite(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return inviteInternal(context, "");
    }

    private static int inviteWithArgs(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String args = StringArgumentType.getString(context, "args");
        return inviteInternal(context, args);
    }

    private static int inviteInternal(CommandContext<CommandSourceStack> context, String args) throws CommandSyntaxException {
        ServerPlayer sender = context.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        
        DuelSettings settings = new DuelSettings();
        
        String[] parts = args.split(" ");
        for (String part : parts) {
            if (part.startsWith("time:")) {
                String timeStr = part.substring(5);
                int time = 120;
                if (timeStr.endsWith("s")) {
                    try { time = Integer.parseInt(timeStr.substring(0, timeStr.length() - 1)); } catch (NumberFormatException ignored) {}
                } else if (timeStr.endsWith("m")) {
                    try { time = Integer.parseInt(timeStr.substring(0, timeStr.length() - 1)) * 60; } catch (NumberFormatException ignored) {}
                } else {
                     try { time = Integer.parseInt(timeStr); } catch (NumberFormatException ignored) {}
                }
                settings.setDurationSeconds(time);
            } else if (part.startsWith("winhp:")) {
                String hpStr = part.substring(6);
                int hp = 0; // Default to 0% (death) if parsing fails
                try {
                    if (hpStr.endsWith("%")) {
                        hp = Integer.parseInt(hpStr.substring(0, hpStr.length() - 1));
                    } else {
                        hp = Integer.parseInt(hpStr);
                    }
                } catch (NumberFormatException ignored) {
                    // hp remains 1
                }
                
                if (hp < 0) {
                    hp = 0;
                } else if (hp > 100) {
                    hp = 100;
                }
                settings.setWinHpPercentage(hp);
            }
        }
        
        DuelManager.sendRequest(sender, target, settings);
        return 1;
    }

    private static int accept(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        DuelManager.acceptAnyRequest(player);
        return 1;
    }
    
    private static int decline(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        DuelManager.declineRequest(player, target.getUUID());
        return 1;
    }
    
    private static int stats(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        PlayerStats stats = StatsManager.getStats(player.getUUID());
        
        player.sendSystemMessage(Component.literal("--- Your Duel Stats ---"));
        player.sendSystemMessage(Component.literal("Wins: " + stats.getWins()));
        player.sendSystemMessage(Component.literal("Losses: " + stats.getLosses()));
        player.sendSystemMessage(Component.literal("Draws: " + stats.getDraws()));
        
        return 1;
    }
    
    private static int startBattle(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String args = StringArgumentType.getString(context, "args");
        CommandSourceStack source = context.getSource();
        
        Map<String, List<ServerPlayer>> teams = new HashMap<>();
        BattleSettings settings = new BattleSettings();
        Set<UUID> assignedPlayers = new HashSet<>();
        
        String[] parts = args.split(" ");
        String currentTeam = null;
        
        for (String part : parts) {
            if (part.endsWith(":")) {
                currentTeam = part.substring(0, part.length() - 1);
                teams.put(currentTeam, new ArrayList<>());
            } else if (currentTeam != null) {
                if (part.startsWith("time:")) {
                    String timeStr = part.substring(5);
                    int time = 300;
                    if (timeStr.endsWith("s")) {
                        try { time = Integer.parseInt(timeStr.substring(0, timeStr.length() - 1)); } catch (NumberFormatException ignored) {}
                    } else if (timeStr.endsWith("m")) {
                        try { time = Integer.parseInt(timeStr.substring(0, timeStr.length() - 1)) * 60; } catch (NumberFormatException ignored) {}
                    }
                    settings.setDurationSeconds(time);
                    currentTeam = null; 
                } else {
                    try {
                        ServerPlayer player = null;
                        for(ServerPlayer p : source.getServer().getPlayerList().getPlayers()){
                            if(p.getName().getString().equalsIgnoreCase(part)){
                                player = p;
                                break;
                            }
                        }

                        if(player != null){
                            if (!assignedPlayers.add(player.getUUID())) {
                                source.sendFailure(Component.literal("Player " + player.getName().getString() + " cannot be in multiple teams."));
                                return 0;
                            }
                            teams.get(currentTeam).add(player);
                        } else {
                            source.sendFailure(Component.literal("Player not found: " + part));
                        }

                    } catch (Exception e) {
                        source.sendFailure(Component.literal("Invalid player name: " + part));
                    }
                }
            }
        }
        
        if (teams.size() < 2) {
            source.sendFailure(Component.literal("A battle requires at least two teams."));
            return 0;
        }
        
        if (BattleManager.startBattle(source.getServer(), teams, settings)) {
            source.sendSuccess(() -> Component.literal("Battle started!"), true);
        } else {
            source.sendFailure(Component.literal("A battle is already in progress or one of the players is busy."));
        }

        return 1;
    }
    
    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        ConfigManager.loadConfig();
        context.getSource().sendSuccess(() -> Component.literal("Duels config reloaded."), true);
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
        context.getSource().sendFailure(Component.literal("Unknown mode. Use 1v1 or 2v2."));
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

    private static int arenaCreate(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        ServerPlayer player = context.getSource().getPlayerOrException();
        boolean created = ArenaManager.createArena(name, player.level().dimension());
        if (created) {
            context.getSource().sendSuccess(() -> Component.literal("Arena created: " + name), true);
        } else {
            context.getSource().sendFailure(Component.literal("Arena already exists: " + name));
        }
        return 1;
    }

    private static int arenaRemove(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        boolean removed = ArenaManager.removeArena(name);
        if (removed) {
            context.getSource().sendSuccess(() -> Component.literal("Arena removed: " + name), true);
        } else {
            context.getSource().sendFailure(Component.literal("Arena not found: " + name));
        }
        return 1;
    }

    private static int arenaPos1(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        ServerPlayer player = context.getSource().getPlayerOrException();
        ArenaManager.Arena arena = ArenaManager.getArena(name);
        if (arena == null) {
            context.getSource().sendFailure(Component.literal("Arena not found: " + name));
            return 0;
        }
        if (!arena.dimensionId.equals(player.level().dimension().location().toString())) {
            context.getSource().sendFailure(Component.literal("You must be in the arena's dimension to set pos1."));
            return 0;
        }
        ArenaManager.setPos1(name, player.blockPosition());
        context.getSource().sendSuccess(() -> Component.literal("Arena pos1 set for " + name), true);
        return 1;
    }

    private static int arenaPos2(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        ServerPlayer player = context.getSource().getPlayerOrException();
        ArenaManager.Arena arena = ArenaManager.getArena(name);
        if (arena == null) {
            context.getSource().sendFailure(Component.literal("Arena not found: " + name));
            return 0;
        }
        if (!arena.dimensionId.equals(player.level().dimension().location().toString())) {
            context.getSource().sendFailure(Component.literal("You must be in the arena's dimension to set pos2."));
            return 0;
        }
        ArenaManager.setPos2(name, player.blockPosition());
        context.getSource().sendSuccess(() -> Component.literal("Arena pos2 set for " + name), true);
        return 1;
    }

    private static int arenaAddSpawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        int team = IntegerArgumentType.getInteger(context, "team");
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        ServerPlayer player = context.getSource().getPlayerOrException();
        ArenaManager.Arena arena = ArenaManager.getArena(name);
        if (arena == null) {
            context.getSource().sendFailure(Component.literal("Arena not found: " + name));
            return 0;
        }
        if (!arena.dimensionId.equals(player.level().dimension().location().toString())) {
            context.getSource().sendFailure(Component.literal("You must be in the arena's dimension to add spawns."));
            return 0;
        }
        boolean ok = ArenaManager.addSpawn(name, team, pos);
        if (ok) {
            context.getSource().sendSuccess(() -> Component.literal("Spawn added to " + name + " team " + team), true);
        } else {
            context.getSource().sendFailure(Component.literal("Failed to add spawn."));
        }
        return 1;
    }

    private static int arenaRemoveSpawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        int team = IntegerArgumentType.getInteger(context, "team");
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        ArenaManager.Arena arena = ArenaManager.getArena(name);
        if (arena == null) {
            context.getSource().sendFailure(Component.literal("Arena not found: " + name));
            return 0;
        }
        boolean ok = ArenaManager.removeSpawn(name, team, pos);
        if (ok) {
            context.getSource().sendSuccess(() -> Component.literal("Spawn removed from " + name + " team " + team), true);
        } else {
            context.getSource().sendFailure(Component.literal("Spawn not found."));
        }
        return 1;
    }

    private static int arenaList(CommandContext<CommandSourceStack> context) {
        List<ArenaManager.Arena> list = ArenaManager.listArenas();
        if (list.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("No arenas defined."), false);
            return 1;
        }
        context.getSource().sendSuccess(() -> Component.literal("Arenas:"), false);
        for (ArenaManager.Arena arena : list) {
            context.getSource().sendSuccess(() -> Component.literal(
                "- " + arena.name + " (T1: " + arena.team1Spawns.size() + ", T2: " + arena.team2Spawns.size() + ")"
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
