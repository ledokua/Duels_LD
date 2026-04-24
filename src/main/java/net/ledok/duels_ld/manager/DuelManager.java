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
    private static final Map<UUID, PlayerBackup> duelBackups = new ConcurrentHashMap<>();
    private static final String DUEL_TEAM_PREFIX = "duel_team_";
    private static final Map<UUID, Long> lastBoundsTeleport = new ConcurrentHashMap<>();
    private static final long BOUNDS_TELEPORT_COOLDOWN_MS = 2000;
    private static final double COUNTDOWN_FREEZE_THRESHOLD_SQR = 0.25;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(DuelManager::onServerTick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (duelBackups.containsKey(player.getUUID()) && !isInDuel(player)) {
                restorePlayerFromBackup(player);
                player.sendSystemMessage(Component.translatable("duels_ld.duel.restore_gamemode").withStyle(ChatFormatting.YELLOW));
            }
        });
        
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && entity instanceof ServerPlayer targetPlayer) {
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
                ActiveDuel duel = getDuel(serverPlayer);
                if (duel != null && duel.isCountdown()) {
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && isInDuel(serverPlayer)) {
                ActiveDuel duel = getDuel(serverPlayer);
                if (duel != null && duel.isCountdown()) {
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer serverPlayer && isInDuel(serverPlayer)) {
                ActiveDuel duel = getDuel(serverPlayer);
                if (duel != null && duel.isCountdown()) {
                    return InteractionResultHolder.fail(player.getItemInHand(hand));
                }
            }
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer player) {
                Entity attacker = source.getEntity();
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
                endDuel(player.server, duel, false, winnerUUID);
            }
        });

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer player) || !isInDuel(player)) {
                return true;
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
    }
    
    public static boolean isInDuel(ServerPlayer player) {
        return activeDuels.containsKey(player.getUUID());
    }
    
    public static ActiveDuel getDuel(ServerPlayer player) {
        return activeDuels.get(player.getUUID());
    }

    public static boolean isPlayerInArena(ServerPlayer player) {
        if (!isInDuel(player)) {
            return false;
        }
        ActiveDuel duel = getDuel(player);
        return duel != null && duel.getArenaName() != null;
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

        restorePlayer(player1, duel);
        restorePlayer(player2, duel);
        restoreHealth(player1, duel);
        restoreHealth(player2, duel);

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
    
    private static void restorePlayer(ServerPlayer player, ActiveDuel duel) {
        if (player == null) return;

        PlayerBackup backup = duelBackups.remove(player.getUUID());
        if (backup != null) {
            player.setGameMode(backup.getGameMode());
            ServerLevel level = player.server.getLevel(backup.getDimension());
            if (duel.isMatchmaking() && level != null) {
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
    
    private static void restoreHealth(ServerPlayer player, ActiveDuel duel) {
        if (player == null) return;

        float startHp = player.getUUID().equals(duel.getPlayer1()) ? duel.getPlayer1StartHp() : duel.getPlayer2StartHp();
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
}
