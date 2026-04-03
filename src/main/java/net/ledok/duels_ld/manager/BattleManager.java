package net.ledok.duels_ld.manager;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.level.GameType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class BattleManager {
    private static ActiveBattle activeBattle = null;
    private static final Map<UUID, PlayerBackup> playerBackups = new ConcurrentHashMap<>();
    private static final double SPECTATOR_TETHER_DISTANCE = 30.0;
    private static final Logger LOGGER = LoggerFactory.getLogger(DuelsLdMod.MOD_ID);
    private static int matchmakingCounter = 0;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(BattleManager::onServerTick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (playerBackups.containsKey(player.getUUID())) {
                if (activeBattle != null && isPlayerInBattle(player.getUUID())) {
                    player.setGameMode(GameType.SPECTATOR);
                    player.sendSystemMessage(Component.translatable("duels_ld.battle.reconnect_spectator").withStyle(ChatFormatting.YELLOW));
                } else {
                    restorePlayer(player);
                }
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInBattle(serverPlayer.getUUID())) {
                if (activeBattle != null && activeBattle.isCountdown()) {
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInBattle(serverPlayer.getUUID())) {
                if (activeBattle != null && activeBattle.isCountdown()) {
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInBattle(serverPlayer.getUUID())) {
                if (activeBattle != null && activeBattle.isCountdown()) {
                    return InteractionResultHolder.fail(player.getItemInHand(hand));
                }
            }
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (activeBattle == null || !(entity instanceof ServerPlayer)) return true;

            ServerPlayer damagedPlayer = (ServerPlayer) entity;
            Entity attacker = source.getEntity();

            if (!isPlayerInBattle(damagedPlayer.getUUID())) return true;

            if (activeBattle.isCountdown() || activeBattle.isEliminated(damagedPlayer.getUUID())) return false;

            if (attacker instanceof ServerPlayer) {
                ServerPlayer attackingPlayer = (ServerPlayer) attacker;
                if (!isPlayerInBattle(attackingPlayer.getUUID())) {
                    return false; 
                }
                if (areOnSameTeam(damagedPlayer.getUUID(), attackingPlayer.getUUID())
                    && !attackingPlayer.getUUID().equals(damagedPlayer.getUUID())) {
                    return false; 
                }
                MatchmakingConfigManager.PointWeights weights = MatchmakingConfigManager.getConfig().weights;
                float effectiveAmount = calculateEffectiveDamage(damagedPlayer, source, amount);
                if (!attackingPlayer.getUUID().equals(damagedPlayer.getUUID()) && effectiveAmount > 0) {
                    activeBattle.getPoints(attackingPlayer.getUUID()).addOffense(effectiveAmount * weights.offensePerDamage);
                }
                if (damagedPlayer.isBlocking()) {
                    activeBattle.getPoints(damagedPlayer.getUUID()).addDefense(effectiveAmount * weights.defensePerBlocked);
                }
            }
            
            return true;
        });

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (activeBattle == null || !(entity instanceof ServerPlayer player)) {
                return true;
            }
            if (!isPlayerInBattle(player.getUUID())) {
                return true;
            }
            LOGGER.info("[Duels] Battle prevent death: player={}, source={}, health={}",
                player.getGameProfile().getName(),
                source.getMsgId(),
                player.getHealth());
            player.setHealth(player.getMaxHealth());
            handlePlayerDeath(player.server, player.getUUID());
            return false;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (activeBattle != null && entity instanceof ServerPlayer player && isPlayerInBattle(player.getUUID())) {
                LOGGER.info("[Duels] Battle player death: player={}, source={}, health={}",
                    player.getGameProfile().getName(),
                    source.getMsgId(),
                    player.getHealth());
                handlePlayerDeath(player.server, player.getUUID());
            }
        });
    }

    public static boolean startBattle(MinecraftServer server, Map<String, List<ServerPlayer>> teams, BattleSettings settings) {
        if (activeBattle != null) {
            return false;
        }
        
        for (List<ServerPlayer> team : teams.values()) {
            for (ServerPlayer player : team) {
                if (ActivityManager.isPlayerBusy(player.getUUID())) {
                    return false; 
                }
            }
        }

        Map<String, List<UUID>> teamUUIDs = new HashMap<>();
        for (Map.Entry<String, List<ServerPlayer>> entry : teams.entrySet()) {
            teamUUIDs.put(entry.getKey(), entry.getValue().stream().map(ServerPlayer::getUUID).collect(Collectors.toList()));
        }

        activeBattle = new ActiveBattle(teamUUIDs, settings);
        
        Scoreboard scoreboard = server.getScoreboard();
        ChatFormatting[] colors = new ChatFormatting[] {
            ChatFormatting.RED,
            ChatFormatting.BLUE,
            ChatFormatting.GREEN,
            ChatFormatting.YELLOW,
            ChatFormatting.AQUA,
            ChatFormatting.LIGHT_PURPLE,
            ChatFormatting.GOLD,
            ChatFormatting.WHITE
        };
        int colorIndex = 0;
        for (String teamName : teams.keySet()) {
            PlayerTeam team = scoreboard.addPlayerTeam(teamName);
            team.setAllowFriendlyFire(false);
            team.setColor(colors[colorIndex % colors.length]);
            team.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.HIDE_FOR_OTHER_TEAMS);
            colorIndex++;
            for (ServerPlayer player : teams.get(teamName)) {
                ActivityManager.setPlayerBusy(player.getUUID());
                scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
                activeBattle.getBossBar().addPlayer(player);
                
                playerBackups.putIfAbsent(player.getUUID(), new PlayerBackup(player.gameMode.getGameModeForPlayer(), player.position(), player.level().dimension()));
                player.setGameMode(GameType.ADVENTURE);
                player.setHealth(player.getMaxHealth());
                player.getFoodData().setFoodLevel(20);
                player.getFoodData().setSaturation(20.0f);
                player.sendSystemMessage(Component.translatable("duels_ld.battle.starting_soon", 10));
                activeBattle.setLastHealth(player.getUUID(), player.getHealth());
            }
        }
        
        return true;
    }

    private static void onServerTick(MinecraftServer server) {
        if (activeBattle == null) return;

        long currentTime = System.currentTimeMillis();

        // Create a copy of the teams to iterate over, to avoid concurrent modification issues
        Map<String, List<UUID>> teamsCopy = new HashMap<>(activeBattle.getTeams());

        for (List<UUID> team : teamsCopy.values()) {
            for (UUID playerUUID : new ArrayList<>(team)) {
                if (activeBattle == null) return; // Stop if battle ended mid-loop

                if (!activeBattle.isEliminated(playerUUID) && server.getPlayerList().getPlayer(playerUUID) == null) {
                    handlePlayerDeath(server, playerUUID);
                }
            }
        }
        
        if (activeBattle == null) return; // Check again as handlePlayerDeath might have ended the battle

        if (activeBattle.isCountdown()) {
            int seconds = activeBattle.getCountdownSeconds();
            activeBattle.getBossBar().setName(Component.translatable("duels_ld.battle.boss_starting", seconds));
            activeBattle.getBossBar().setProgress((float)seconds / 10);

            // Freeze players
            for (List<UUID> teamMembers : activeBattle.getTeams().values()) {
                for (UUID playerUUID : teamMembers) {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
                    if (player != null) {
                        player.teleportTo(player.getX(), player.getY(), player.getZ());
                        player.setDeltaMovement(0, 0, 0);
                        player.hurtMarked = true;
                    }
                }
            }

            activeBattle.incrementCountdownTicks();
            if (activeBattle.getCountdownTicks() >= 20) {
                activeBattle.resetCountdownTicks();
                activeBattle.decrementCountdown();
                if (seconds <= 1) {
                    activeBattle.setCountdown(false);
                    activeBattle.setStartTime(currentTime);
                    activeBattle.getBossBar().setColor(BossEvent.BossBarColor.BLUE);
                    clearSpellCooldowns(server);
                    broadcastToAll(server, Component.translatable("duels_ld.battle.fight"));
                }
            }
        } else {
            long elapsed = (currentTime - activeBattle.getStartTime()) / 1000;
            long remaining = activeBattle.getSettings().getDurationSeconds() - elapsed;

            if (remaining <= 0) {
                String winningTeam = pickWinningTeamByPoints();
                if (winningTeam == null) {
                    endBattle(server, true, null);
                } else {
                    endBattle(server, false, winningTeam);
                }
                return;
            }

            String timeLeft = String.format("%02d:%02d", remaining / 60, remaining % 60);
            activeBattle.getBossBar().setName(Component.translatable("duels_ld.battle.time_left", timeLeft));
            activeBattle.getBossBar().setProgress((float)remaining / activeBattle.getSettings().getDurationSeconds());
            
            tetherSpectators(server);
        }

        enforceArenaBounds(server);
        updateSupportPoints(server);
    }
    
    private static void handlePlayerDeath(MinecraftServer server, UUID playerUUID) {
        if (activeBattle == null) return;
        
        ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
        if (player != null) {
            player.setHealth(player.getMaxHealth());
            player.setGameMode(GameType.SPECTATOR);
            player.sendSystemMessage(Component.translatable("duels_ld.battle.eliminated"));
        }
        
        activeBattle.eliminatePlayer(playerUUID);
        checkEndCondition(server);
    }
    
    private static void checkEndCondition(MinecraftServer server) {
        if (activeBattle == null) return;
        
        List<String> activeTeams = activeBattle.getTeams().entrySet().stream()
            .filter(entry -> entry.getValue().stream().anyMatch(uuid -> !activeBattle.isEliminated(uuid)))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
            
        if (activeTeams.size() <= 1) {
            endBattle(server, activeTeams.isEmpty(), activeTeams.isEmpty() ? null : activeTeams.get(0));
        }
    }

    private static void endBattle(MinecraftServer server, boolean draw, String winningTeamName) {
        if (activeBattle == null) return;

        if (draw) {
            broadcastToAll(server, Component.translatable("duels_ld.battle.draw"));
        } else if (winningTeamName != null) {
            for (Map.Entry<String, List<UUID>> entry : activeBattle.getTeams().entrySet()) {
                boolean isWinner = entry.getKey().equals(winningTeamName);
                for (UUID playerUUID : entry.getValue()) {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
                    if (player != null) {
                        player.sendSystemMessage(Component.translatable(isWinner ? "duels_ld.battle.team_won" : "duels_ld.battle.team_lost"));
                    }
                }
            }
        } else {
            broadcastToAll(server, Component.translatable("duels_ld.battle.ended"));
        }
        
        Scoreboard scoreboard = server.getScoreboard();
        for (String teamName : activeBattle.getTeams().keySet()) {
            PlayerTeam team = scoreboard.getPlayerTeam(teamName);
            if (team != null) {
                scoreboard.removePlayerTeam(team);
            }
        }

        Set<UUID> allPlayers = new HashSet<>();
        activeBattle.getTeams().values().forEach(allPlayers::addAll);

        for (UUID playerUUID : allPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
            if (player != null) {
                restorePlayer(player);
            }
            ActivityManager.setPlayerFree(playerUUID);
        }
        
        if (draw) {
            allPlayers.forEach(StatsManager::recordDraw);
        } else if (winningTeamName != null) {
            for (Map.Entry<String, List<UUID>> entry : activeBattle.getTeams().entrySet()) {
                if (entry.getKey().equals(winningTeamName)) {
                    entry.getValue().forEach(StatsManager::recordWin);
                } else {
                    entry.getValue().forEach(StatsManager::recordLoss);
                }
            }
        }

        activeBattle.getBossBar().removeAllPlayers();
        if (activeBattle.isMatchmaking() && !draw && winningTeamName != null) {
            List<UUID> winners = activeBattle.getTeams().get(winningTeamName);
            if (winners != null && winners.size() == 2) {
                List<UUID> losers = new ArrayList<>();
                for (Map.Entry<String, List<UUID>> entry : activeBattle.getTeams().entrySet()) {
                    if (!entry.getKey().equals(winningTeamName)) {
                        losers.addAll(entry.getValue());
                    }
                }
                if (losers.size() == 2) {
                    MMRManager.EloDelta2v2 delta = MMRManager.applyResult2v2WithDelta(winners.get(0), winners.get(1), losers.get(0), losers.get(1));
                    for (UUID winnerId : winners) {
                        ServerPlayer player = server.getPlayerList().getPlayer(winnerId);
                        if (player != null) {
                            player.sendSystemMessage(Component.translatable("duels_ld.battle.elo_gain", formatEloDelta(delta.winnerDelta)));
                        }
                    }
                    for (UUID loserId : losers) {
                        ServerPlayer player = server.getPlayerList().getPlayer(loserId);
                        if (player != null) {
                            player.sendSystemMessage(Component.translatable("duels_ld.battle.elo_loss", formatEloDelta(delta.loserDelta)));
                        }
                    }
                }
            }
        }
        if (activeBattle.getArenaName() != null) {
            ArenaManager.markInactive(activeBattle.getArenaName());
        }
        activeBattle = null;
    }
    
    private static void restorePlayer(ServerPlayer player) {
        if (player == null) return;
        
        PlayerBackup backup = playerBackups.remove(player.getUUID());
        if (backup != null) {
            player.setGameMode(backup.getGameMode());
            ServerLevel level = player.server.getLevel(backup.getDimension());
            if (level != null) {
                player.teleportTo(level, backup.getPosition().x, backup.getPosition().y, backup.getPosition().z, player.getYRot(), player.getXRot());
            } else {
                player.teleportTo(backup.getPosition().x, backup.getPosition().y, backup.getPosition().z);
            }
        }
        player.setHealth(player.getMaxHealth());
    }

    private static String formatEloDelta(int delta) {
        return (delta > 0 ? "+" : "") + delta;
    }
    
    private static void tetherSpectators(MinecraftServer server) {
        if (activeBattle == null) return;

        List<ServerPlayer> activePlayers = new ArrayList<>();
        for (List<UUID> team : activeBattle.getTeams().values()) {
            for (UUID playerUUID : team) {
                if (!activeBattle.isEliminated(playerUUID)) {
                    ServerPlayer p = server.getPlayerList().getPlayer(playerUUID);
                    if (p != null) activePlayers.add(p);
                }
            }
        }

        if (activePlayers.isEmpty()) return;

        for (UUID spectatorUUID : activeBattle.getEliminatedPlayers()) {
            ServerPlayer spectator = server.getPlayerList().getPlayer(spectatorUUID);
            if (spectator == null) continue;

            ServerPlayer closestPlayer = null;
            double minDistanceSq = Double.MAX_VALUE;

            for (ServerPlayer activePlayer : activePlayers) {
                double distSq = spectator.distanceToSqr(activePlayer);
                if (distSq < minDistanceSq) {
                    minDistanceSq = distSq;
                    closestPlayer = activePlayer;
                }
            }

            if (closestPlayer != null && minDistanceSq > SPECTATOR_TETHER_DISTANCE * SPECTATOR_TETHER_DISTANCE) {
                spectator.teleportTo(closestPlayer.getX(), closestPlayer.getY(), closestPlayer.getZ());
            }
        }
    }

    public static boolean isPlayerInBattle(UUID playerUUID) {
        if (activeBattle == null) return false;
        return activeBattle.getTeams().values().stream().anyMatch(list -> list.contains(playerUUID));
    }

    public static boolean isPlayerInArena(ServerPlayer player) {
        return activeBattle != null
            && activeBattle.getArenaName() != null
            && isPlayerInBattle(player.getUUID());
    }

    private static boolean areOnSameTeam(UUID player1, UUID player2) {
        if (activeBattle == null) return false;
        String team1 = getTeamName(player1);
        String team2 = getTeamName(player2);
        return team1 != null && team1.equals(team2);
    }
    
    private static String getTeamName(UUID playerUUID) {
        if (activeBattle == null) return null;
        for (Map.Entry<String, List<UUID>> entry : activeBattle.getTeams().entrySet()) {
            if (entry.getValue().contains(playerUUID)) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    private static void broadcastToAll(MinecraftServer server, Component message) {
        if (activeBattle == null) return;
        
        LOGGER.info("[Duels] " + message.getString());
        Set<UUID> allPlayers = new HashSet<>();
        activeBattle.getTeams().values().forEach(allPlayers::addAll);
        
        for (UUID playerUUID : allPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
            if (player != null) {
                player.sendSystemMessage(message);
            }
        }
    }

    public static boolean startMatchmakingBattle(MinecraftServer server, ServerPlayer p1, ServerPlayer p2, ServerPlayer p3, ServerPlayer p4, BattleSettings settings,
                                                 ArenaManager.Arena arena, Vec3 t1a, Vec3 t1b, Vec3 t2a, Vec3 t2b) {
        if (arena != null) {
            playerBackups.putIfAbsent(p1.getUUID(), new PlayerBackup(p1.gameMode.getGameModeForPlayer(), p1.position(), p1.level().dimension()));
            playerBackups.putIfAbsent(p2.getUUID(), new PlayerBackup(p2.gameMode.getGameModeForPlayer(), p2.position(), p2.level().dimension()));
            playerBackups.putIfAbsent(p3.getUUID(), new PlayerBackup(p3.gameMode.getGameModeForPlayer(), p3.position(), p3.level().dimension()));
            playerBackups.putIfAbsent(p4.getUUID(), new PlayerBackup(p4.gameMode.getGameModeForPlayer(), p4.position(), p4.level().dimension()));
            ServerLevel level = server.getLevel(arena.getDimensionKey());
            if (level != null) {
                p1.teleportTo(level, t1a.x, t1a.y, t1a.z, p1.getYRot(), p1.getXRot());
                p2.teleportTo(level, t1b.x, t1b.y, t1b.z, p2.getYRot(), p2.getXRot());
                p3.teleportTo(level, t2a.x, t2a.y, t2a.z, p3.getYRot(), p3.getXRot());
                p4.teleportTo(level, t2b.x, t2b.y, t2b.z, p4.getYRot(), p4.getXRot());
            }
        }
        String teamA = "mm_team_" + matchmakingCounter++ + "_a";
        String teamB = "mm_team_" + matchmakingCounter++ + "_b";
        Map<String, List<ServerPlayer>> teams = new HashMap<>();
        teams.put(teamA, List.of(p1, p2));
        teams.put(teamB, List.of(p3, p4));
        boolean started = startBattle(server, teams, settings);
        if (started && activeBattle != null && arena != null) {
            Scoreboard scoreboard = server.getScoreboard();
            PlayerTeam teamOne = scoreboard.getPlayerTeam(teamA);
            PlayerTeam teamTwo = scoreboard.getPlayerTeam(teamB);
            if (teamOne != null) {
                teamOne.setPlayerPrefix(Component.translatable("duels_ld.arena.prefix", arena.name));
            }
            if (teamTwo != null) {
                teamTwo.setPlayerPrefix(Component.translatable("duels_ld.arena.prefix", arena.name));
            }
            activeBattle.setArenaName(arena.name);
            activeBattle.setSpawnPosition(p1.getUUID(), t1a);
            activeBattle.setSpawnPosition(p2.getUUID(), t1b);
            activeBattle.setSpawnPosition(p3.getUUID(), t2a);
            activeBattle.setSpawnPosition(p4.getUUID(), t2b);
            activeBattle.setMatchmaking(true);
        }
        return started;
    }

    private static void updateSupportPoints(MinecraftServer server) {
        if (activeBattle == null) return;
        MatchmakingConfigManager.PointWeights weights = MatchmakingConfigManager.getConfig().weights;
        for (List<UUID> team : activeBattle.getTeams().values()) {
            for (UUID playerUUID : team) {
                if (activeBattle.isEliminated(playerUUID)) {
                    continue;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
                if (player == null) {
                    continue;
                }
                float last = activeBattle.getLastHealth(playerUUID);
                float current = player.getHealth();
                if (current > last) {
                    activeBattle.getPoints(playerUUID).addSupport((current - last) * weights.supportPerHeal);
                }
                activeBattle.setLastHealth(playerUUID, current);
            }
        }
    }

    private static void enforceArenaBounds(MinecraftServer server) {
        if (activeBattle == null || activeBattle.getArenaName() == null) {
            return;
        }
        ArenaManager.Arena arena = ArenaManager.getArena(activeBattle.getArenaName());
        if (arena == null || arena.pos1 == null || arena.pos2 == null) {
            return;
        }
        double minX = Math.min(arena.pos1.getX(), arena.pos2.getX());
        double maxX = Math.max(arena.pos1.getX(), arena.pos2.getX()) + 1;
        double minY = Math.min(arena.pos1.getY(), arena.pos2.getY());
        double maxY = Math.max(arena.pos1.getY(), arena.pos2.getY()) + 1;
        double minZ = Math.min(arena.pos1.getZ(), arena.pos2.getZ());
        double maxZ = Math.max(arena.pos1.getZ(), arena.pos2.getZ()) + 1;

        for (List<UUID> team : activeBattle.getTeams().values()) {
            for (UUID playerUUID : team) {
                if (activeBattle.isEliminated(playerUUID)) {
                    continue;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
                if (player == null) {
                    continue;
                }
                if (!player.level().dimension().equals(arena.getDimensionKey())) {
                    teleportToSpawn(player, arena);
                    continue;
                }
                double x = player.getX();
                double y = player.getY();
                double z = player.getZ();
                if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
                    teleportToSpawn(player, arena);
                }
            }
        }
    }

    private static void teleportToSpawn(ServerPlayer player, ArenaManager.Arena arena) {
        Vec3 spawn = activeBattle.getSpawnPosition(player.getUUID());
        if (spawn == null) {
            return;
        }
        ServerLevel level = player.server.getLevel(arena.getDimensionKey());
        if (level != null) {
            player.teleportTo(level, spawn.x, spawn.y, spawn.z, player.getYRot(), player.getXRot());
        }
    }

    private static void clearSpellCooldowns(MinecraftServer server) {
        if (activeBattle == null || server == null) {
            return;
        }
        for (List<UUID> team : activeBattle.getTeams().values()) {
            for (UUID playerUUID : team) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
                if (player != null) {
                    server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withSuppressedOutput().withPermission(4),
                        "spell_cooldown clear " + player.getGameProfile().getName()
                    );
                }
            }
        }
    }

    private static String pickWinningTeamByPoints() {
        if (activeBattle == null) return null;
        double bestScore = Double.NEGATIVE_INFINITY;
        String bestTeam = null;
        boolean tie = false;

        for (Map.Entry<String, List<UUID>> entry : activeBattle.getTeams().entrySet()) {
            double teamScore = 0.0;
            for (UUID playerUUID : entry.getValue()) {
                teamScore += activeBattle.getPoints(playerUUID).getTotal();
            }
            if (teamScore > bestScore) {
                bestScore = teamScore;
                bestTeam = entry.getKey();
                tie = false;
            } else if (teamScore == bestScore) {
                tie = true;
            }
        }

        return tie ? null : bestTeam;
    }

    private static float applyResistance(ServerPlayer player, float amount) {
        MobEffectInstance effect = player.getEffect(MobEffects.DAMAGE_RESISTANCE);
        if (effect == null) {
            return amount;
        }
        int level = effect.getAmplifier();
        float reduction = 0.2f * (level + 1);
        if (reduction >= 1.0f) {
            return 0.0f;
        }
        return amount * (1.0f - reduction);
    }

    private static float calculateEffectiveDamage(ServerPlayer player, net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (player.isBlocking()) {
            return 0.0f;
        }
        float armor = player.getArmorValue();
        float toughness = (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float afterArmor = CombatRules.getDamageAfterAbsorb(player, amount, source, armor, toughness);
        return applyResistance(player, afterArmor);
    }
}
