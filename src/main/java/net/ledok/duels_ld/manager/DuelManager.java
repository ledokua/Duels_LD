package net.ledok.duels_ld.manager;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
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
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.InteractionResultHolder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DuelManager {
    private static final Map<UUID, ActiveDuel> activeDuels = new ConcurrentHashMap<>();
    private static final Map<UUID, ActiveTeamDuel> activeTeamDuels = new ConcurrentHashMap<>();
    private static final Map<UUID, PlayerBackup> duelBackups = new ConcurrentHashMap<>();
    private static final String DUEL_TEAM_PREFIX = "duel_team_";
    private static final Map<UUID, Long> lastBoundsTeleport = new ConcurrentHashMap<>();
    private static final long BOUNDS_TELEPORT_COOLDOWN_MS = 2000;
    private static final double COUNTDOWN_FREEZE_THRESHOLD_SQR = 0.25;
    private static final double SPECTATOR_LEASH_RADIUS = 8.0;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(DuelManager::onServerTick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            UUID playerId = player.getUUID();
            if (!duelBackups.containsKey(playerId)) {
                return;
            }
            ActiveTeamDuel teamDuel = activeTeamDuels.get(playerId);
            if (teamDuel != null) {
                if (teamDuel.isEliminated(playerId)) {
                    player.setGameMode(GameType.SPECTATOR);
                }
                return;
            }
            if (!activeDuels.containsKey(playerId)) {
                restorePlayerFromBackup(player);
                player.sendSystemMessage(Component.translatable("duels_ld.duel.restore_gamemode").withStyle(ChatFormatting.YELLOW));
            }
        });
        
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && entity instanceof ServerPlayer targetPlayer) {
                ActiveTeamDuel attackerTeamDuel = getTeamDuel(serverPlayer);
                ActiveTeamDuel targetTeamDuel = getTeamDuel(targetPlayer);
                if (attackerTeamDuel != null || targetTeamDuel != null) {
                    if (attackerTeamDuel == null || targetTeamDuel == null || attackerTeamDuel != targetTeamDuel) {
                        return InteractionResult.FAIL;
                    }
                    if (attackerTeamDuel.isCountdown()) {
                        return InteractionResult.FAIL;
                    }
                    if (attackerTeamDuel.getTeam(serverPlayer.getUUID()) == attackerTeamDuel.getTeam(targetPlayer.getUUID())
                        && !serverPlayer.getUUID().equals(targetPlayer.getUUID())) {
                        return InteractionResult.FAIL;
                    }
                    return InteractionResult.PASS;
                }
                if (isInDuel(serverPlayer) && isInDuel(targetPlayer)) {
                    ActiveDuel duel = getDuel(serverPlayer);
                    if (duel != null && duel.isCountdown()) {
                        return InteractionResult.FAIL;
                    }
                    if (duel != null && (!duel.getPlayer1().equals(targetPlayer.getUUID()) && !duel.getPlayer2().equals(targetPlayer.getUUID()))) {
                        return InteractionResult.FAIL;
                    }
                } else if (isInDuel(serverPlayer) || isInDuel(targetPlayer)) {
                    return InteractionResult.FAIL;
                }
            } else if (player instanceof ServerPlayer serverPlayer && isInDuel(serverPlayer)) {
                 // Allow attacking mobs
            }
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && isInDuel(serverPlayer)) {
                ActiveTeamDuel teamDuel = getTeamDuel(serverPlayer);
                if (teamDuel != null && teamDuel.isCountdown()) {
                    return InteractionResult.FAIL;
                }
                ActiveDuel duel = getDuel(serverPlayer);
                if (duel != null && duel.isCountdown()) {
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && isInDuel(serverPlayer)) {
                ActiveTeamDuel teamDuel = getTeamDuel(serverPlayer);
                if (teamDuel != null && teamDuel.isCountdown()) {
                    return InteractionResult.FAIL;
                }
                ActiveDuel duel = getDuel(serverPlayer);
                if (duel != null && duel.isCountdown()) {
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer serverPlayer && isInDuel(serverPlayer)) {
                ActiveTeamDuel teamDuel = getTeamDuel(serverPlayer);
                if (teamDuel != null && teamDuel.isCountdown()) {
                    return InteractionResultHolder.fail(player.getItemInHand(hand));
                }
                ActiveDuel duel = getDuel(serverPlayer);
                if (duel != null && duel.isCountdown()) {
                    return InteractionResultHolder.fail(player.getItemInHand(hand));
                }
            }
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer player) {
                ActiveTeamDuel teamDuel = getTeamDuel(player);
                Entity attacker = source.getEntity();
                if (teamDuel != null) {
                    if (teamDuel.isCountdown() || teamDuel.isEliminated(player.getUUID())) {
                        return false;
                    }
                    if (attacker instanceof ServerPlayer attackerPlayer) {
                        ActiveTeamDuel attackerTeamDuel = getTeamDuel(attackerPlayer);
                        if (attackerTeamDuel != teamDuel) {
                            teamDuel.markForEnd(true, 0);
                            return false;
                        }
                        if (teamDuel.isEliminated(attackerPlayer.getUUID())) {
                            return false;
                        }
                        int attackedTeam = teamDuel.getTeam(player.getUUID());
                        int attackerTeam = teamDuel.getTeam(attackerPlayer.getUUID());
                        if (attackedTeam == attackerTeam && !attackerPlayer.getUUID().equals(player.getUUID())) {
                            return false;
                        }
                        MatchmakingConfigManager.PointWeights weights = MatchmakingConfigManager.getConfig().weights;
                        float effectiveAmount = calculateEffectiveDamage(player, source, amount);
                        if (!attackerPlayer.getUUID().equals(player.getUUID()) && effectiveAmount > 0) {
                            teamDuel.getPoints(attackerPlayer.getUUID()).addOffense(effectiveAmount * weights.offensePerDamage);
                        }
                        if (player.isBlocking()) {
                            teamDuel.getPoints(player.getUUID()).addDefense(effectiveAmount * weights.defensePerBlocked);
                        }
                        return true;
                    }
                    float effectiveAmount = calculateEffectiveDamage(player, source, amount);
                    if (player.isBlocking()) {
                        MatchmakingConfigManager.PointWeights weights = MatchmakingConfigManager.getConfig().weights;
                        teamDuel.getPoints(player.getUUID()).addDefense(effectiveAmount * weights.defensePerBlocked);
                    }
                    return true;
                }
                if (attacker instanceof ServerPlayer attackerPlayer) {
                     if (isInDuel(player) && isInDuel(attackerPlayer)) {
                        ActiveDuel duel = getDuel(player);
                        if (duel != null && duel.isCountdown()) {
                            return false;
                        }
                        if (duel != null && (duel.getPlayer1().equals(attackerPlayer.getUUID()) || duel.getPlayer2().equals(attackerPlayer.getUUID()))) {
                            MatchmakingConfigManager.PointWeights weights = MatchmakingConfigManager.getConfig().weights;
                            float effectiveAmount = calculateEffectiveDamage(player, source, amount);
                            if (!attackerPlayer.getUUID().equals(player.getUUID()) && effectiveAmount > 0) {
                                duel.getPoints(attackerPlayer.getUUID()).addOffense(effectiveAmount * weights.offensePerDamage);
                            }
                            if (player.isBlocking()) {
                                duel.getPoints(player.getUUID()).addDefense(effectiveAmount * weights.defensePerBlocked);
                            }
                            return true;
                        }
                        return false;
                    } else if (isInDuel(player) || isInDuel(attackerPlayer)) {
                        if (isInDuel(player)) {
                             ActiveDuel duel = getDuel(player);
                             if (duel != null) {
                                 duel.markForEnd(true, null);
                             }
                        }
                        return false;
                    }
                } else if (isInDuel(player)) {
                     ActiveDuel duel = getDuel(player);
                     if (duel != null) {
                         float effectiveAmount = calculateEffectiveDamage(player, source, amount);
                         if (player.isBlocking()) {
                             MatchmakingConfigManager.PointWeights weights = MatchmakingConfigManager.getConfig().weights;
                             duel.getPoints(player.getUUID()).addDefense(effectiveAmount * weights.defensePerBlocked);
                         }
                     }
                }
            }
            return true;
        });

        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (!(entity instanceof ServerPlayer player)) {
                return;
            }
            if (!isInDuel(player)) {
                return;
            }
            ActiveTeamDuel teamDuel = getTeamDuel(player);
            if (teamDuel != null) {
                if (teamDuel.isCountdown()) {
                    return;
                }
                int winHp = teamDuel.getSettings().getWinHpPercentage();
                if (winHp <= 0) {
                    return;
                }
                float healthPct = (player.getHealth() / player.getMaxHealth()) * 100.0f;
                if (healthPct <= winHp && player.getHealth() > 0.0f) {
                    int loserTeam = teamDuel.getTeam(player.getUUID());
                    int winningTeam = otherTeam(loserTeam);
                    Entity attacker = source.getEntity();
                    if (attacker instanceof ServerPlayer attackerPlayer && getTeamDuel(attackerPlayer) == teamDuel) {
                        int attackerTeam = teamDuel.getTeam(attackerPlayer.getUUID());
                        if (attackerTeam != 0) {
                            winningTeam = attackerTeam;
                        }
                    }
                    teamDuel.markForEnd(false, winningTeam);
                }
                return;
            }
            ActiveDuel duel = getDuel(player);
            if (duel == null || duel.isCountdown()) {
                return;
            }
            int winHp = duel.getSettings().getWinHpPercentage();
            if (winHp <= 0) {
                return;
            }
            float healthPct = (player.getHealth() / player.getMaxHealth()) * 100.0f;
            if (healthPct <= winHp && player.getHealth() > 0.0f) {
                UUID winnerUUID = null;
                Entity attacker = source.getEntity();
                if (attacker instanceof ServerPlayer attackerPlayer) {
                    winnerUUID = attackerPlayer.getUUID().equals(player.getUUID()) ? getOpponentUUID(duel, player.getUUID()) : attackerPlayer.getUUID();
                } else {
                    winnerUUID = getOpponentUUID(duel, player.getUUID());
                }
                duel.markForEnd(false, winnerUUID);
            }
        });

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer player) || !isInDuel(player)) {
                return true;
            }
            ActiveTeamDuel teamDuel = getTeamDuel(player);
            if (teamDuel != null) {
                player.setHealth(player.getMaxHealth());
                handleTeamPlayerDeath(player.server, teamDuel, player.getUUID());
                return false;
            }
            ActiveDuel duel = getDuel(player);
            if (duel == null) {
                return true;
            }
            DuelsLdMod.LOGGER.info("[Duels] Duel prevent death: player={}, source={}, health={}",
                player.getGameProfile().getName(),
                source.getMsgId(),
                player.getHealth());
            UUID winnerUUID = getOpponentUUID(duel, player.getUUID());
            player.setHealth(player.getMaxHealth());
            endDuel(player.server, duel, false, winnerUUID);
            return false;
        });
        
    }

    private static void startDuel(ServerPlayer player1, ServerPlayer player2, DuelSettings settings) {
        ActivityManager.setPlayerBusy(player1.getUUID());
        ActivityManager.setPlayerBusy(player2.getUUID());
        
        Scoreboard scoreboard = player1.getScoreboard();
        String teamName = buildTeamBaseName(scoreboard);
        
        String team1Name = teamName + "_1";
        String team2Name = teamName + "_2";
        PlayerTeam team1 = scoreboard.addPlayerTeam(team1Name);
        PlayerTeam team2 = scoreboard.addPlayerTeam(team2Name);
        team1.setAllowFriendlyFire(false);
        team2.setAllowFriendlyFire(false);
        team1.setColor(ChatFormatting.RED);
        team2.setColor(ChatFormatting.BLUE);
        team1.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.HIDE_FOR_OTHER_TEAMS);
        team2.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.HIDE_FOR_OTHER_TEAMS);
        
        scoreboard.addPlayerToTeam(player1.getScoreboardName(), team1);
        scoreboard.addPlayerToTeam(player2.getScoreboardName(), team2);
        
        duelBackups.putIfAbsent(player1.getUUID(), new PlayerBackup(player1.gameMode.getGameModeForPlayer(), player1.position(), player1.level().dimension()));
        duelBackups.putIfAbsent(player2.getUUID(), new PlayerBackup(player2.gameMode.getGameModeForPlayer(), player2.position(), player2.level().dimension()));
        
        player1.setGameMode(GameType.ADVENTURE);
        player2.setGameMode(GameType.ADVENTURE);

        ActiveDuel duel = new ActiveDuel(player1.getUUID(), player2.getUUID(), settings, teamName, player1.getHealth(), player2.getHealth());
        activeDuels.put(player1.getUUID(), duel);
        activeDuels.put(player2.getUUID(), duel);
        duel.setFreezePosition(player1.getUUID(), player1.position());
        duel.setFreezePosition(player2.getUUID(), player2.position());
        
        duel.getBossBar().addPlayer(player1);
        duel.getBossBar().addPlayer(player2);

        player1.setHealth(player1.getMaxHealth());
        player2.setHealth(player2.getMaxHealth());
        player1.getFoodData().setFoodLevel(20);
        player2.getFoodData().setFoodLevel(20);
        player1.getFoodData().setSaturation(20.0f);
        player2.getFoodData().setSaturation(20.0f);

        player1.sendSystemMessage(Component.translatable("duels_ld.duel.starting_soon", 10));
        player2.sendSystemMessage(Component.translatable("duels_ld.duel.starting_soon", 10));
    }

    public static void startMatchmakingDuel(ServerPlayer player1, ServerPlayer player2, DuelSettings settings, ArenaManager.Arena arena, Vec3 spawn1, Vec3 spawn2) {
        if (arena != null) {
            duelBackups.put(player1.getUUID(), new PlayerBackup(player1.gameMode.getGameModeForPlayer(), player1.position(), player1.level().dimension()));
            duelBackups.put(player2.getUUID(), new PlayerBackup(player2.gameMode.getGameModeForPlayer(), player2.position(), player2.level().dimension()));

            ServerLevel level = player1.server.getLevel(arena.getDimensionKey());
            if (level != null) {
                player1.teleportTo(level, spawn1.x, spawn1.y, spawn1.z, player1.getYRot(), player1.getXRot());
                player2.teleportTo(level, spawn2.x, spawn2.y, spawn2.z, player2.getYRot(), player2.getXRot());
            }
        }
        startDuel(player1, player2, settings);
        ActiveDuel duel = getDuel(player1);
        if (duel != null && arena != null) {
            Scoreboard scoreboard = player1.getScoreboard();
            PlayerTeam team1 = scoreboard.getPlayerTeam(duel.getTeamName() + "_1");
            PlayerTeam team2 = scoreboard.getPlayerTeam(duel.getTeamName() + "_2");
            if (team1 != null) {
                team1.setPlayerPrefix(Component.translatable("duels_ld.arena.prefix", arena.name));
            }
            if (team2 != null) {
                team2.setPlayerPrefix(Component.translatable("duels_ld.arena.prefix", arena.name));
            }
            duel.setArenaName(arena.name);
            duel.setSpawnPosition(player1.getUUID(), spawn1);
            duel.setSpawnPosition(player2.getUUID(), spawn2);
            duel.setMatchmaking(true);
        }
    }

    public static void startMatchmakingDuel2v2(MinecraftServer server, ServerPlayer p1, ServerPlayer p2, ServerPlayer p3, ServerPlayer p4,
                                                DuelSettings settings, ArenaManager.Arena arena, Vec3 t1a, Vec3 t1b, Vec3 t2a, Vec3 t2b) {
        duelBackups.putIfAbsent(p1.getUUID(), new PlayerBackup(p1.gameMode.getGameModeForPlayer(), p1.position(), p1.level().dimension()));
        duelBackups.putIfAbsent(p2.getUUID(), new PlayerBackup(p2.gameMode.getGameModeForPlayer(), p2.position(), p2.level().dimension()));
        duelBackups.putIfAbsent(p3.getUUID(), new PlayerBackup(p3.gameMode.getGameModeForPlayer(), p3.position(), p3.level().dimension()));
        duelBackups.putIfAbsent(p4.getUUID(), new PlayerBackup(p4.gameMode.getGameModeForPlayer(), p4.position(), p4.level().dimension()));

        if (arena != null) {
            ServerLevel level = server.getLevel(arena.getDimensionKey());
            if (level != null) {
                p1.teleportTo(level, t1a.x, t1a.y, t1a.z, p1.getYRot(), p1.getXRot());
                p2.teleportTo(level, t1b.x, t1b.y, t1b.z, p2.getYRot(), p2.getXRot());
                p3.teleportTo(level, t2a.x, t2a.y, t2a.z, p3.getYRot(), p3.getXRot());
                p4.teleportTo(level, t2b.x, t2b.y, t2b.z, p4.getYRot(), p4.getXRot());
            }
        }

        ActivityManager.setPlayerBusy(p1.getUUID());
        ActivityManager.setPlayerBusy(p2.getUUID());
        ActivityManager.setPlayerBusy(p3.getUUID());
        ActivityManager.setPlayerBusy(p4.getUUID());

        Scoreboard scoreboard = server.getScoreboard();
        String teamName = buildTeamBaseName(scoreboard);

        String team1Name = teamName + "_1";
        String team2Name = teamName + "_2";
        PlayerTeam team1 = scoreboard.addPlayerTeam(team1Name);
        PlayerTeam team2 = scoreboard.addPlayerTeam(team2Name);
        team1.setAllowFriendlyFire(false);
        team2.setAllowFriendlyFire(false);
        team1.setColor(ChatFormatting.RED);
        team2.setColor(ChatFormatting.BLUE);
        team1.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.HIDE_FOR_OTHER_TEAMS);
        team2.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.HIDE_FOR_OTHER_TEAMS);
        scoreboard.addPlayerToTeam(p1.getScoreboardName(), team1);
        scoreboard.addPlayerToTeam(p2.getScoreboardName(), team1);
        scoreboard.addPlayerToTeam(p3.getScoreboardName(), team2);
        scoreboard.addPlayerToTeam(p4.getScoreboardName(), team2);

        p1.setGameMode(GameType.ADVENTURE);
        p2.setGameMode(GameType.ADVENTURE);
        p3.setGameMode(GameType.ADVENTURE);
        p4.setGameMode(GameType.ADVENTURE);

        Map<UUID, Float> startHealth = new HashMap<>();
        startHealth.put(p1.getUUID(), p1.getHealth());
        startHealth.put(p2.getUUID(), p2.getHealth());
        startHealth.put(p3.getUUID(), p3.getHealth());
        startHealth.put(p4.getUUID(), p4.getHealth());
        ActiveTeamDuel duel = new ActiveTeamDuel(
            List.of(p1.getUUID(), p2.getUUID()),
            List.of(p3.getUUID(), p4.getUUID()),
            settings,
            teamName,
            startHealth
        );
        for (UUID playerId : duel.allPlayers()) {
            activeTeamDuels.put(playerId, duel);
        }

        duel.getBossBar().addPlayer(p1);
        duel.getBossBar().addPlayer(p2);
        duel.getBossBar().addPlayer(p3);
        duel.getBossBar().addPlayer(p4);

        p1.setHealth(p1.getMaxHealth());
        p2.setHealth(p2.getMaxHealth());
        p3.setHealth(p3.getMaxHealth());
        p4.setHealth(p4.getMaxHealth());
        p1.getFoodData().setFoodLevel(20);
        p2.getFoodData().setFoodLevel(20);
        p3.getFoodData().setFoodLevel(20);
        p4.getFoodData().setFoodLevel(20);
        p1.getFoodData().setSaturation(20.0f);
        p2.getFoodData().setSaturation(20.0f);
        p3.getFoodData().setSaturation(20.0f);
        p4.getFoodData().setSaturation(20.0f);

        p1.sendSystemMessage(Component.translatable("duels_ld.duel.starting_soon", 10));
        p2.sendSystemMessage(Component.translatable("duels_ld.duel.starting_soon", 10));
        p3.sendSystemMessage(Component.translatable("duels_ld.duel.starting_soon", 10));
        p4.sendSystemMessage(Component.translatable("duels_ld.duel.starting_soon", 10));

        duel.setFreezePosition(p1.getUUID(), p1.position());
        duel.setFreezePosition(p2.getUUID(), p2.position());
        duel.setFreezePosition(p3.getUUID(), p3.position());
        duel.setFreezePosition(p4.getUUID(), p4.position());

        if (arena != null) {
            PlayerTeam scoreboardTeam1 = scoreboard.getPlayerTeam(team1Name);
            PlayerTeam scoreboardTeam2 = scoreboard.getPlayerTeam(team2Name);
            if (scoreboardTeam1 != null) {
                scoreboardTeam1.setPlayerPrefix(Component.translatable("duels_ld.arena.prefix", arena.name));
            }
            if (scoreboardTeam2 != null) {
                scoreboardTeam2.setPlayerPrefix(Component.translatable("duels_ld.arena.prefix", arena.name));
            }
            duel.setArenaName(arena.name);
            duel.setSpawnPosition(p1.getUUID(), t1a);
            duel.setSpawnPosition(p2.getUUID(), t1b);
            duel.setSpawnPosition(p3.getUUID(), t2a);
            duel.setSpawnPosition(p4.getUUID(), t2b);
            duel.setMatchmaking(true);
        }
    }
    
    private static void onServerTick(MinecraftServer server) {
        long currentTime = System.currentTimeMillis();
        Set<ActiveDuel> processedDuels = new HashSet<>();
        
        for (ActiveDuel duel : activeDuels.values()) {
            if (processedDuels.contains(duel)) continue;
            processedDuels.add(duel);
            
            ServerPlayer p1 = server.getPlayerList().getPlayer(duel.getPlayer1());
            ServerPlayer p2 = server.getPlayerList().getPlayer(duel.getPlayer2());
            
            if (p1 == null && p2 == null) {
                endDuel(server, duel, true, null);
                continue;
            }
            if (p1 == null) {
                endDuel(server, duel, false, duel.getPlayer2());
                continue;
            }
            if (p2 == null) {
                endDuel(server, duel, false, duel.getPlayer1());
                continue;
            }

            ActiveDuel.EndRequest pendingEndRequest = duel.consumePendingEndRequest();
            if (pendingEndRequest != null) {
                endDuel(server, duel, pendingEndRequest.isDraw(), pendingEndRequest.getWinnerUUID());
                continue;
            }

            if (duel.isCountdown()) {
                maybeEnforceCountdownFreeze(duel, p1);
                maybeEnforceCountdownFreeze(duel, p2);
                p1.setDeltaMovement(0, 0, 0);
                p2.setDeltaMovement(0, 0, 0);
                p1.hurtMarked = true;
                p2.hurtMarked = true;
                
                duel.incrementCountdownTicks();
                if (duel.getCountdownTicks() >= 20) {
                    duel.resetCountdownTicks();
                    duel.decrementCountdown();
                    
                    if (duel.getCountdownSeconds() <= 0) {
                        duel.setCountdown(false);
                        duel.setStartTime(currentTime);
                        duel.getBossBar().setName(Component.translatable("duels_ld.duel.started"));
                        duel.getBossBar().setColor(BossEvent.BossBarColor.BLUE);
                        p1.sendSystemMessage(Component.translatable("duels_ld.duel.fight"));
                        p2.sendSystemMessage(Component.translatable("duels_ld.duel.fight"));
                    } else {
                        duel.getBossBar().setName(Component.translatable("duels_ld.duel.boss_starting", duel.getCountdownSeconds()));
                        duel.getBossBar().setProgress((float)duel.getCountdownSeconds() / 10.0f);
                    }
                }
            } else {
                long elapsed = (currentTime - duel.getStartTime()) / 1000;
                long remaining = duel.getSettings().getDurationSeconds() - elapsed;
                
                if (remaining <= 0) {
                    UUID winnerByPoints = pickWinnerByPoints(duel);
                    if (winnerByPoints == null) {
                        endDuel(server, duel, true, null);
                    } else {
                        endDuel(server, duel, false, winnerByPoints);
                    }
                    continue;
                }
                
                String timeLeft = String.format("%02d:%02d", remaining / 60, remaining % 60);
                duel.getBossBar().setName(Component.translatable("duels_ld.duel.time_left", timeLeft));
                duel.getBossBar().setProgress((float)remaining / duel.getSettings().getDurationSeconds());
            }

            if (duel.isMatchmaking()) {
                enforceArenaBounds(duel, p1, p2);
            }
            updateSupportPoints(duel, p1);
            updateSupportPoints(duel, p2);
        }

        processActiveTeamDuels(server, currentTime);
    }
    
    public static boolean isInDuel(ServerPlayer player) {
        UUID playerId = player.getUUID();
        return activeDuels.containsKey(playerId) || activeTeamDuels.containsKey(playerId);
    }
    
    public static ActiveDuel getDuel(ServerPlayer player) {
        return activeDuels.get(player.getUUID());
    }

    private static ActiveTeamDuel getTeamDuel(ServerPlayer player) {
        return activeTeamDuels.get(player.getUUID());
    }

    public static boolean isPlayerInArena(ServerPlayer player) {
        ActiveDuel duel = getDuel(player);
        if (duel != null && duel.getArenaName() != null) {
            return true;
        }
        ActiveTeamDuel teamDuel = getTeamDuel(player);
        return teamDuel != null && teamDuel.getArenaName() != null;
    }

    private static void endDuel(MinecraftServer server, ActiveDuel duel, boolean draw, UUID winnerUUID) {
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team1 = scoreboard.getPlayerTeam(duel.getTeamName() + "_1");
        PlayerTeam team2 = scoreboard.getPlayerTeam(duel.getTeamName() + "_2");
        if (team1 != null) scoreboard.removePlayerTeam(team1);
        if (team2 != null) scoreboard.removePlayerTeam(team2);

        activeDuels.remove(duel.getPlayer1());
        activeDuels.remove(duel.getPlayer2());
        lastBoundsTeleport.remove(duel.getPlayer1());
        lastBoundsTeleport.remove(duel.getPlayer2());
        
        ActivityManager.setPlayerFree(duel.getPlayer1());
        ActivityManager.setPlayerFree(duel.getPlayer2());
        
        duel.getBossBar().removeAllPlayers();
        duel.getBossBar().setVisible(false);

        ServerPlayer player1 = server.getPlayerList().getPlayer(duel.getPlayer1());
        ServerPlayer player2 = server.getPlayerList().getPlayer(duel.getPlayer2());

        restorePlayer(player1, duel.isMatchmaking());
        restorePlayer(player2, duel.isMatchmaking());
        restoreHealth(player1, duel.getPlayer1StartHp());
        restoreHealth(player2, duel.getPlayer2StartHp());

        if (draw) {
            if (player1 != null) {
                player1.sendSystemMessage(Component.translatable("duels_ld.duel.draw"));
                StatsManager.recordDraw(player1.getUUID());
            }
            if (player2 != null) {
                player2.sendSystemMessage(Component.translatable("duels_ld.duel.draw"));
                StatsManager.recordDraw(player2.getUUID());
            }
        } else if (winnerUUID != null) {
            UUID loserUUID = duel.getPlayer1().equals(winnerUUID) ? duel.getPlayer2() : duel.getPlayer1();
            
            StatsManager.recordWin(winnerUUID);
            StatsManager.recordLoss(loserUUID);

            MatchmakingConfigManager.PointWeights weights = MatchmakingConfigManager.getConfig().weights;
            duel.getPoints(winnerUUID).addOffense(weights.killBonus);

            if (duel.isMatchmaking()) {
                MMRManager.EloDelta1v1 delta = MMRManager.applyResult1v1WithDelta(winnerUUID, loserUUID);
                ServerPlayer winner = server.getPlayerList().getPlayer(winnerUUID);
                ServerPlayer loser = server.getPlayerList().getPlayer(loserUUID);
                if (winner != null) {
                    winner.sendSystemMessage(Component.translatable("duels_ld.duel.win_elo", formatEloDelta(delta.winnerDelta)));
                }
                if (loser != null) {
                    loser.sendSystemMessage(Component.translatable("duels_ld.duel.loss_elo", formatEloDelta(delta.loserDelta)));
                }
            } else {
                ServerPlayer winner = server.getPlayerList().getPlayer(winnerUUID);
                ServerPlayer loser = server.getPlayerList().getPlayer(loserUUID);

                if (winner != null) winner.sendSystemMessage(Component.translatable("duels_ld.duel.win"));
                if (loser != null) loser.sendSystemMessage(Component.translatable("duels_ld.duel.loss"));
            }
        }

        if (duel.getArenaName() != null) {
            ArenaManager.markInactive(duel.getArenaName());
        }
    }

    private static void endTeamDuel(MinecraftServer server, ActiveTeamDuel duel, boolean draw, int winningTeam) {
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team1 = scoreboard.getPlayerTeam(duel.getTeamNameBase() + "_1");
        PlayerTeam team2 = scoreboard.getPlayerTeam(duel.getTeamNameBase() + "_2");
        if (team1 != null) {
            scoreboard.removePlayerTeam(team1);
        }
        if (team2 != null) {
            scoreboard.removePlayerTeam(team2);
        }

        List<UUID> allPlayers = duel.allPlayers();
        for (UUID id : allPlayers) {
            activeTeamDuels.remove(id);
            lastBoundsTeleport.remove(id);
            ActivityManager.setPlayerFree(id);
        }

        duel.getBossBar().removeAllPlayers();
        duel.getBossBar().setVisible(false);

        for (UUID id : allPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            restorePlayer(player, duel.isMatchmaking());
            restoreHealth(player, duel.getStartHealth(id));
        }

        if (draw) {
            for (UUID id : allPlayers) {
                StatsManager.recordDraw(id);
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) {
                    player.sendSystemMessage(Component.translatable("duels_ld.duel.draw"));
                }
            }
        } else {
            List<UUID> winners = winningTeam == 1 ? duel.getTeam1() : duel.getTeam2();
            List<UUID> losers = winningTeam == 1 ? duel.getTeam2() : duel.getTeam1();
            for (UUID id : winners) {
                StatsManager.recordWin(id);
            }
            for (UUID id : losers) {
                StatsManager.recordLoss(id);
            }
            if (duel.isMatchmaking() && winners.size() >= 2 && losers.size() >= 2) {
                MMRManager.EloDelta2v2 delta = MMRManager.applyResult2v2WithDelta(
                    winners.get(0), winners.get(1), losers.get(0), losers.get(1)
                );
                for (UUID id : winners) {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    if (player != null) {
                        player.sendSystemMessage(Component.translatable("duels_ld.duel.win_elo", formatEloDelta(delta.winnerDelta)));
                    }
                }
                for (UUID id : losers) {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    if (player != null) {
                        player.sendSystemMessage(Component.translatable("duels_ld.duel.loss_elo", formatEloDelta(delta.loserDelta)));
                    }
                }
            } else {
                for (UUID id : winners) {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    if (player != null) {
                        player.sendSystemMessage(Component.translatable("duels_ld.duel.win"));
                    }
                }
                for (UUID id : losers) {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    if (player != null) {
                        player.sendSystemMessage(Component.translatable("duels_ld.duel.loss"));
                    }
                }
            }
        }

        if (duel.getArenaName() != null) {
            ArenaManager.markInactive(duel.getArenaName());
        }
    }
    
    private static void restorePlayer(ServerPlayer player, boolean shouldTeleportFromBackup) {
        if (player == null) return;

        PlayerBackup backup = duelBackups.remove(player.getUUID());
        if (backup != null) {
            player.setGameMode(backup.getGameMode());
            ServerLevel level = player.server.getLevel(backup.getDimension());
            if (shouldTeleportFromBackup && level != null) {
                player.teleportTo(level, backup.getPosition().x, backup.getPosition().y, backup.getPosition().z, player.getYRot(), player.getXRot());
            }
        }
    }

    private static String formatEloDelta(int delta) {
        return (delta > 0 ? "+" : "") + delta;
    }

    private static void restorePlayerFromBackup(ServerPlayer player) {
        PlayerBackup backup = duelBackups.remove(player.getUUID());
        if (backup != null) {
            player.setGameMode(backup.getGameMode());
            ServerLevel level = player.server.getLevel(backup.getDimension());
            if (level != null) {
                player.teleportTo(level, backup.getPosition().x, backup.getPosition().y, backup.getPosition().z, player.getYRot(), player.getXRot());
            } else {
                player.teleportTo(backup.getPosition().x, backup.getPosition().y, backup.getPosition().z);
            }
        }
    }
    
    private static void restoreHealth(ServerPlayer player, float startHp) {
        if (player == null) return;
        float maxHp = player.getMaxHealth();

        // Keep post-duel health from dropping below a floor, but do not heal above half-max.
        float halfMax = maxHp * 0.5f;
        float finalTarget = Math.min(startHp, halfMax);
        
        if (player.getHealth() < finalTarget) {
            player.setHealth(finalTarget);
        }
    }

    private static void updateSupportPoints(ActiveDuel duel, ServerPlayer player) {
        if (player == null) return;
        float last = duel.getLastHealth(player.getUUID());
        float current = player.getHealth();
        if (current > last) {
            MatchmakingConfigManager.PointWeights weights = MatchmakingConfigManager.getConfig().weights;
            duel.getPoints(player.getUUID()).addSupport((current - last) * weights.supportPerHeal);
        }
        duel.setLastHealth(player.getUUID(), current);
    }

    private static UUID pickWinnerByPoints(ActiveDuel duel) {
        double p1 = duel.getPoints(duel.getPlayer1()).getTotal();
        double p2 = duel.getPoints(duel.getPlayer2()).getTotal();
        if (p1 == p2) {
            return null;
        }
        return p1 > p2 ? duel.getPlayer1() : duel.getPlayer2();
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

    private static UUID getOpponentUUID(ActiveDuel duel, UUID playerUUID) {
        return duel.getPlayer1().equals(playerUUID) ? duel.getPlayer2() : duel.getPlayer1();
    }

    private static int otherTeam(int team) {
        return team == 1 ? 2 : 1;
    }

    private static void processActiveTeamDuels(MinecraftServer server, long currentTime) {
        Set<ActiveTeamDuel> processed = new HashSet<>();
        for (ActiveTeamDuel duel : activeTeamDuels.values()) {
            if (!processed.add(duel)) {
                continue;
            }

            ActiveTeamDuel.EndRequest pendingEnd = duel.consumePendingEndRequest();
            if (pendingEnd != null) {
                endTeamDuel(server, duel, pendingEnd.isDraw(), pendingEnd.getWinningTeam());
                continue;
            }

            if (duel.isCountdown()) {
                for (UUID playerId : duel.allPlayers()) {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null) {
                        continue;
                    }
                    maybeEnforceCountdownFreeze(duel, player);
                    player.setDeltaMovement(0, 0, 0);
                    player.hurtMarked = true;
                }

                duel.incrementCountdownTicks();
                if (duel.getCountdownTicks() >= 20) {
                    duel.resetCountdownTicks();
                    duel.decrementCountdown();
                    if (duel.getCountdownSeconds() <= 0) {
                        duel.setCountdown(false);
                        duel.setStartTime(currentTime);
                        duel.getBossBar().setName(Component.translatable("duels_ld.duel.started"));
                        duel.getBossBar().setColor(BossEvent.BossBarColor.BLUE);
                        for (UUID playerId : duel.allPlayers()) {
                            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                            if (player != null) {
                                player.sendSystemMessage(Component.translatable("duels_ld.duel.fight"));
                            }
                        }
                    } else {
                        duel.getBossBar().setName(Component.translatable("duels_ld.duel.boss_starting", duel.getCountdownSeconds()));
                        duel.getBossBar().setProgress((float) duel.getCountdownSeconds() / 10.0f);
                    }
                }
            } else {
                for (UUID playerId : duel.allPlayers()) {
                    if (duel.isEliminated(playerId)) {
                        continue;
                    }
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null) {
                        handleTeamPlayerDeath(server, duel, playerId);
                    }
                }
                if (!isTeamDuelActive(duel)) {
                    continue;
                }

                long elapsed = (currentTime - duel.getStartTime()) / 1000;
                long remaining = duel.getSettings().getDurationSeconds() - elapsed;
                if (remaining <= 0) {
                    int winningTeam = pickWinningTeamByPoints(duel);
                    if (winningTeam == 0) {
                        endTeamDuel(server, duel, true, 0);
                    } else {
                        endTeamDuel(server, duel, false, winningTeam);
                    }
                    continue;
                }
                String timeLeft = String.format("%02d:%02d", remaining / 60, remaining % 60);
                duel.getBossBar().setName(Component.translatable("duels_ld.duel.time_left", timeLeft));
                duel.getBossBar().setProgress((float) remaining / duel.getSettings().getDurationSeconds());
            }

            enforceSpectatorLeash(duel, server);
            if (duel.isMatchmaking()) {
                enforceArenaBounds(duel, server);
            }
            updateSupportPoints(duel, server);
        }
    }

    private static void handleTeamPlayerDeath(MinecraftServer server, ActiveTeamDuel duel, UUID playerId) {
        if (!isTeamDuelActive(duel) || duel.isEliminated(playerId)) {
            return;
        }
        duel.eliminate(playerId);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.setHealth(player.getMaxHealth());
            player.setGameMode(GameType.SPECTATOR);
        }
        boolean team1Alive = duel.hasAlivePlayersInTeam(1);
        boolean team2Alive = duel.hasAlivePlayersInTeam(2);
        if (!team1Alive && !team2Alive) {
            endTeamDuel(server, duel, true, 0);
            return;
        }
        if (!team1Alive) {
            endTeamDuel(server, duel, false, 2);
            return;
        }
        if (!team2Alive) {
            endTeamDuel(server, duel, false, 1);
        }
    }

    private static boolean isTeamDuelActive(ActiveTeamDuel duel) {
        for (UUID playerId : duel.allPlayers()) {
            if (activeTeamDuels.get(playerId) == duel) {
                return true;
            }
        }
        return false;
    }

    private static int pickWinningTeamByPoints(ActiveTeamDuel duel) {
        double team1Score = 0.0;
        double team2Score = 0.0;
        for (UUID id : duel.getTeam1()) {
            team1Score += duel.getPoints(id).getTotal();
        }
        for (UUID id : duel.getTeam2()) {
            team2Score += duel.getPoints(id).getTotal();
        }
        if (team1Score == team2Score) {
            return 0;
        }
        return team1Score > team2Score ? 1 : 2;
    }

    private static void enforceSpectatorLeash(ActiveTeamDuel duel, MinecraftServer server) {
        double leashDistanceSqr = SPECTATOR_LEASH_RADIUS * SPECTATOR_LEASH_RADIUS;
        for (UUID playerId : duel.allPlayers()) {
            if (!duel.isEliminated(playerId)) {
                continue;
            }
            ServerPlayer spectator = server.getPlayerList().getPlayer(playerId);
            if (spectator == null) {
                continue;
            }
            List<UUID> team = duel.getTeam(playerId) == 1 ? duel.getTeam1() : duel.getTeam2();
            ServerPlayer nearestAliveTeammate = null;
            double nearestDistanceSqr = Double.MAX_VALUE;
            for (UUID teammateId : team) {
                if (teammateId.equals(playerId) || duel.isEliminated(teammateId)) {
                    continue;
                }
                ServerPlayer teammate = server.getPlayerList().getPlayer(teammateId);
                if (teammate == null) {
                    continue;
                }
                double distanceSqr = spectator.distanceToSqr(teammate);
                if (distanceSqr < nearestDistanceSqr) {
                    nearestDistanceSqr = distanceSqr;
                    nearestAliveTeammate = teammate;
                }
            }
            if (nearestAliveTeammate == null || nearestDistanceSqr <= leashDistanceSqr) {
                continue;
            }
            if (!spectator.level().dimension().equals(nearestAliveTeammate.level().dimension())) {
                continue;
            }
            spectator.teleportTo(
                nearestAliveTeammate.getX() + 0.6,
                nearestAliveTeammate.getY() + 0.1,
                nearestAliveTeammate.getZ() + 0.6
            );
        }
    }

    private static void updateSupportPoints(ActiveTeamDuel duel, MinecraftServer server) {
        MatchmakingConfigManager.PointWeights weights = MatchmakingConfigManager.getConfig().weights;
        for (UUID playerId : duel.allPlayers()) {
            if (duel.isEliminated(playerId)) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                continue;
            }
            float last = duel.getLastHealth(playerId);
            float current = player.getHealth();
            if (current > last) {
                duel.getPoints(playerId).addSupport((current - last) * weights.supportPerHeal);
            }
            duel.setLastHealth(playerId, current);
        }
    }

    private static void maybeEnforceCountdownFreeze(ActiveDuel duel, ServerPlayer player) {
        Vec3 frozenPos = duel.getFreezePosition(player.getUUID());
        if (frozenPos == null) {
            frozenPos = player.position();
            duel.setFreezePosition(player.getUUID(), frozenPos);
            return;
        }
        if (player.position().distanceToSqr(frozenPos) > COUNTDOWN_FREEZE_THRESHOLD_SQR) {
            player.teleportTo(frozenPos.x, frozenPos.y, frozenPos.z);
        }
    }

    private static void maybeEnforceCountdownFreeze(ActiveTeamDuel duel, ServerPlayer player) {
        Vec3 frozenPos = duel.getFreezePosition(player.getUUID());
        if (frozenPos == null) {
            frozenPos = player.position();
            duel.setFreezePosition(player.getUUID(), frozenPos);
            return;
        }
        if (player.position().distanceToSqr(frozenPos) > COUNTDOWN_FREEZE_THRESHOLD_SQR) {
            player.teleportTo(frozenPos.x, frozenPos.y, frozenPos.z);
        }
    }

    private static String buildTeamBaseName(Scoreboard scoreboard) {
        for (int i = 0; i < 8; i++) {
            String id = UUID.randomUUID().toString().replace("-", "");
            String teamBase = DUEL_TEAM_PREFIX + id.substring(0, 4);
            if (scoreboard.getPlayerTeam(teamBase + "_1") == null && scoreboard.getPlayerTeam(teamBase + "_2") == null) {
                return teamBase;
            }
        }
        throw new IllegalStateException("Failed to allocate a unique duel team name.");
    }

    private static void enforceArenaBounds(ActiveDuel duel, ServerPlayer p1, ServerPlayer p2) {
        if (duel.getArenaName() == null) {
            return;
        }
        ArenaManager.Arena arena = ArenaManager.getArena(duel.getArenaName());
        if (arena == null || arena.pos1 == null || arena.pos2 == null) {
            return;
        }
        enforceArenaBoundsForPlayer(duel, arena, p1);
        enforceArenaBoundsForPlayer(duel, arena, p2);
    }

    private static void enforceArenaBounds(ActiveTeamDuel duel, MinecraftServer server) {
        if (duel.getArenaName() == null) {
            return;
        }
        ArenaManager.Arena arena = ArenaManager.getArena(duel.getArenaName());
        if (arena == null || arena.pos1 == null || arena.pos2 == null) {
            return;
        }
        for (UUID playerId : duel.allPlayers()) {
            if (duel.isEliminated(playerId)) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                continue;
            }
            if (!player.level().dimension().equals(arena.getDimensionKey())) {
                maybeTeleportToSpawn(duel, arena, player);
                continue;
            }
            double minX = Math.min(arena.pos1.getX(), arena.pos2.getX());
            double maxX = Math.max(arena.pos1.getX(), arena.pos2.getX()) + 1;
            double minY = Math.min(arena.pos1.getY(), arena.pos2.getY());
            double maxY = Math.max(arena.pos1.getY(), arena.pos2.getY()) + 1;
            double minZ = Math.min(arena.pos1.getZ(), arena.pos2.getZ());
            double maxZ = Math.max(arena.pos1.getZ(), arena.pos2.getZ()) + 1;
            double x = player.getX();
            double y = player.getY();
            double z = player.getZ();
            if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
                maybeTeleportToSpawn(duel, arena, player);
            }
        }
    }

    private static void enforceArenaBoundsForPlayer(ActiveDuel duel, ArenaManager.Arena arena, ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (!player.level().dimension().equals(arena.getDimensionKey())) {
            maybeTeleportToSpawn(duel, arena, player);
            return;
        }
        double minX = Math.min(arena.pos1.getX(), arena.pos2.getX());
        double maxX = Math.max(arena.pos1.getX(), arena.pos2.getX()) + 1;
        double minY = Math.min(arena.pos1.getY(), arena.pos2.getY());
        double maxY = Math.max(arena.pos1.getY(), arena.pos2.getY()) + 1;
        double minZ = Math.min(arena.pos1.getZ(), arena.pos2.getZ());
        double maxZ = Math.max(arena.pos1.getZ(), arena.pos2.getZ()) + 1;

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
            maybeTeleportToSpawn(duel, arena, player);
        }
    }

    private static void maybeTeleportToSpawn(ActiveDuel duel, ArenaManager.Arena arena, ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = lastBoundsTeleport.get(player.getUUID());
        if (last != null && now - last < BOUNDS_TELEPORT_COOLDOWN_MS) {
            return;
        }
        lastBoundsTeleport.put(player.getUUID(), now);
        teleportToSpawn(duel, arena, player);
    }

    private static void maybeTeleportToSpawn(ActiveTeamDuel duel, ArenaManager.Arena arena, ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = lastBoundsTeleport.get(player.getUUID());
        if (last != null && now - last < BOUNDS_TELEPORT_COOLDOWN_MS) {
            return;
        }
        lastBoundsTeleport.put(player.getUUID(), now);
        teleportToSpawn(duel, arena, player);
    }

    private static void teleportToSpawn(ActiveDuel duel, ArenaManager.Arena arena, ServerPlayer player) {
        Vec3 spawn = duel.getSpawnPosition(player.getUUID());
        if (spawn == null) {
            return;
        }
        ServerLevel level = player.server.getLevel(arena.getDimensionKey());
        if (level != null) {
            player.teleportTo(level, spawn.x, spawn.y, spawn.z, player.getYRot(), player.getXRot());
        }
    }

    private static void teleportToSpawn(ActiveTeamDuel duel, ArenaManager.Arena arena, ServerPlayer player) {
        Vec3 spawn = duel.getSpawnPosition(player.getUUID());
        if (spawn == null) {
            return;
        }
        ServerLevel level = player.server.getLevel(arena.getDimensionKey());
        if (level != null) {
            player.teleportTo(level, spawn.x, spawn.y, spawn.z, player.getYRot(), player.getXRot());
        }
    }
}
