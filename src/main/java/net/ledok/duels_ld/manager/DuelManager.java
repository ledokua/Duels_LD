package net.ledok.duels_ld.manager;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.duels_ld.network.AcceptRequestPayload;
import net.ledok.duels_ld.network.DeclineRequestPayload;
import net.ledok.duels_ld.network.OpenDuelScreenPayload;
import net.ledok.duels_ld.network.SyncRequestsPayload;
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
    private static final Map<UUID, Map<UUID, DuelRequest>> pendingRequests = new HashMap<>(); // Target -> {Sender -> Request}
    private static final Map<UUID, ActiveDuel> activeDuels = new ConcurrentHashMap<>();
    private static final Map<UUID, GameType> gameModeBackups = new ConcurrentHashMap<>();
    private static final Map<UUID, PlayerBackup> duelBackups = new ConcurrentHashMap<>();
    private static final String DUEL_TEAM_PREFIX = "duel_team_";
    private static int teamCounter = 0;
    private static final long REQUEST_EXPIRATION_MS = 60000; // 60 seconds

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(DuelManager::onServerTick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (gameModeBackups.containsKey(player.getUUID())) {
                GameType originalGameMode = gameModeBackups.remove(player.getUUID());
                if (originalGameMode != null) {
                    player.setGameMode(originalGameMode);
                    player.sendSystemMessage(Component.literal("Your gamemode was restored after an incomplete duel.").withStyle(ChatFormatting.YELLOW));
                }
            }
            if (duelBackups.containsKey(player.getUUID()) && !isInDuel(player)) {
                restorePlayerFromBackup(player);
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
                            float healthPct = (player.getHealth() - effectiveAmount) / player.getMaxHealth() * 100;
                            if (healthPct <= duel.getSettings().getWinHpPercentage()) {
                                UUID winner = attackerPlayer.getUUID().equals(player.getUUID()) ? getOpponentUUID(duel, player.getUUID()) : attackerPlayer.getUUID();
                                endDuel(attackerPlayer.server, duel, false, winner);
                                return false;
                            }
                            return true;
                        }
                        return false;
                    } else if (isInDuel(player) || isInDuel(attackerPlayer)) {
                        if (isInDuel(player)) {
                             ActiveDuel duel = getDuel(player);
                             endDuel(player.server, duel, true, null);
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
                         float healthPct = (player.getHealth() - effectiveAmount) / player.getMaxHealth() * 100;
                         if (healthPct <= 0 || healthPct <= duel.getSettings().getWinHpPercentage()) {
                            UUID winnerUUID = getOpponentUUID(duel, player.getUUID());
                            endDuel(player.server, duel, false, winnerUUID);
                            return false;
                        }
                     }
                }
            }
            return true;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player && isInDuel(player)) {
                ActiveDuel duel = getDuel(player);
                if (duel != null) {
                    UUID winnerUUID = getOpponentUUID(duel, player.getUUID());
                    endDuel(player.server, duel, false, winnerUUID);
                }
            }
        });
        
        ServerPlayNetworking.registerGlobalReceiver(AcceptRequestPayload.TYPE, (payload, context) -> context.server().execute(() -> acceptRequest(context.player(), payload.senderId())));
        ServerPlayNetworking.registerGlobalReceiver(DeclineRequestPayload.TYPE, (payload, context) -> context.server().execute(() -> declineRequest(context.player(), payload.senderId())));
    }

    public static void sendRequest(ServerPlayer sender, ServerPlayer target, DuelSettings settings) {
        if (sender.getUUID().equals(target.getUUID())) {
            sender.sendSystemMessage(Component.literal("You cannot duel yourself!"));
            return;
        }
        
        if (ActivityManager.isPlayerBusy(sender.getUUID()) || ActivityManager.isPlayerBusy(target.getUUID())) {
            sender.sendSystemMessage(Component.literal("One of the players is currently busy."));
            return;
        }

        pendingRequests.computeIfAbsent(target.getUUID(), k -> new HashMap<>()).put(sender.getUUID(), new DuelRequest(sender.getUUID(), settings));
        sender.sendSystemMessage(Component.literal("Duel request sent to " + target.getName().getString()));
        
        String settingsStr = "";
        if (settings.getDurationSeconds() != 120) settingsStr += " Time: " + settings.getDurationSeconds() + "s";
        if (settings.getWinHpPercentage() != 0) settingsStr += " WinHP: " + settings.getWinHpPercentage() + "%";
        
        target.sendSystemMessage(Component.literal(sender.getName().getString() + " has requested to duel you." + settingsStr + " Type /duel accept to accept."));
    }
    
    public static void openRequestScreen(ServerPlayer player) {
        cleanupExpiredRequests(player.getUUID());
        ServerPlayNetworking.send(player, new OpenDuelScreenPayload());
        syncRequests(player);
    }
    
    private static void syncRequests(ServerPlayer player) {
        Map<UUID, DuelRequest> requests = pendingRequests.getOrDefault(player.getUUID(), Collections.emptyMap());
        List<SyncRequestsPayload.RequestData> requestDataList = new ArrayList<>();
        
        for (DuelRequest req : requests.values()) {
            ServerPlayer sender = player.server.getPlayerList().getPlayer(req.getSender());
            String senderName = sender != null ? sender.getName().getString() : "Unknown";
            String settingsDesc = "Time: " + req.getSettings().getDurationSeconds() + "s, WinHP: " + req.getSettings().getWinHpPercentage() + "%";
            requestDataList.add(new SyncRequestsPayload.RequestData(req.getSender(), senderName, settingsDesc));
        }
        
        ServerPlayNetworking.send(player, new SyncRequestsPayload(requestDataList));
    }
    
    private static void cleanupExpiredRequests(UUID targetUUID) {
        Map<UUID, DuelRequest> requests = pendingRequests.get(targetUUID);
        if (requests != null) {
            long now = System.currentTimeMillis();
            requests.values().removeIf(req -> now - req.getTimestamp() > REQUEST_EXPIRATION_MS);
            if (requests.isEmpty()) {
                pendingRequests.remove(targetUUID);
            }
        }
    }

    public static void acceptRequest(ServerPlayer target, UUID senderUUID) {
        if (target.getUUID().equals(senderUUID)) {
            target.sendSystemMessage(Component.literal("You cannot duel yourself."));
            return;
        }

        if (ActivityManager.isPlayerBusy(target.getUUID())) {
            target.sendSystemMessage(Component.literal("You are currently busy."));
            return;
        }
        
        cleanupExpiredRequests(target.getUUID());

        Map<UUID, DuelRequest> requests = pendingRequests.get(target.getUUID());
        if (requests == null || !requests.containsKey(senderUUID)) {
            target.sendSystemMessage(Component.literal("This duel request is no longer valid or has expired."));
            return;
        }
        
        DuelRequest request = requests.remove(senderUUID);
        if (requests.isEmpty()) pendingRequests.remove(target.getUUID());

        ServerPlayer sender = target.server.getPlayerList().getPlayer(request.getSender());
        if (sender == null) {
            target.sendSystemMessage(Component.literal("The player who requested the duel is no longer online."));
            return;
        }
        
        if (ActivityManager.isPlayerBusy(sender.getUUID())) {
            target.sendSystemMessage(Component.literal(sender.getName().getString() + " is currently busy."));
            pendingRequests.computeIfAbsent(target.getUUID(), k -> new HashMap<>()).put(senderUUID, request);
            return;
        }
        
        if (sender.distanceTo(target) > 50) {
            target.sendSystemMessage(Component.literal("You are too far away from " + sender.getName().getString() + " (Max 50 blocks)."));
            pendingRequests.computeIfAbsent(target.getUUID(), k -> new HashMap<>()).put(senderUUID, request);
            return;
        }

        startDuel(sender, target, request.getSettings());
    }
    
    public static void declineRequest(ServerPlayer target, UUID senderUUID) {
        Map<UUID, DuelRequest> requests = pendingRequests.get(target.getUUID());
        if (requests != null && requests.containsKey(senderUUID)) {
            requests.remove(senderUUID);
            if (requests.isEmpty()) {
                pendingRequests.remove(target.getUUID());
            }
            
            ServerPlayer sender = target.server.getPlayerList().getPlayer(senderUUID);
            if (sender != null) {
                sender.sendSystemMessage(Component.literal(target.getName().getString() + " has declined your duel request."));
            }
            target.sendSystemMessage(Component.literal("You have declined the duel request."));
        } else {
            target.sendSystemMessage(Component.literal("You do not have a duel request from that player."));
        }
    }
    
    public static void acceptAnyRequest(ServerPlayer target) {
        cleanupExpiredRequests(target.getUUID());
        
        Map<UUID, DuelRequest> requests = pendingRequests.get(target.getUUID());
        if (requests == null || requests.isEmpty()) {
            target.sendSystemMessage(Component.literal("You have no pending duel requests."));
            return;
        }
        
        if (requests.size() == 1) {
            acceptRequest(target, requests.keySet().iterator().next());
        } else {
            openRequestScreen(target);
        }
    }

    private static void startDuel(ServerPlayer player1, ServerPlayer player2, DuelSettings settings) {
        ActivityManager.setPlayerBusy(player1.getUUID());
        ActivityManager.setPlayerBusy(player2.getUUID());
        
        String teamName = DUEL_TEAM_PREFIX + teamCounter++;
        Scoreboard scoreboard = player1.getScoreboard();
        
        String team1Name = teamName + "_1";
        String team2Name = teamName + "_2";
        PlayerTeam team1 = scoreboard.addPlayerTeam(team1Name);
        PlayerTeam team2 = scoreboard.addPlayerTeam(team2Name);
        
        scoreboard.addPlayerToTeam(player1.getScoreboardName(), team1);
        scoreboard.addPlayerToTeam(player2.getScoreboardName(), team2);
        
        GameType p1Gm = player1.gameMode.getGameModeForPlayer();
        GameType p2Gm = player2.gameMode.getGameModeForPlayer();
        
        gameModeBackups.put(player1.getUUID(), p1Gm);
        gameModeBackups.put(player2.getUUID(), p2Gm);
        
        player1.setGameMode(GameType.ADVENTURE);
        player2.setGameMode(GameType.ADVENTURE);

        ActiveDuel duel = new ActiveDuel(player1.getUUID(), player2.getUUID(), settings, teamName, player1.getHealth(), player2.getHealth(), p1Gm, p2Gm);
        activeDuels.put(player1.getUUID(), duel);
        activeDuels.put(player2.getUUID(), duel);
        
        duel.getBossBar().addPlayer(player1);
        duel.getBossBar().addPlayer(player2);

        player1.sendSystemMessage(Component.literal("Duel starting in 10 seconds!"));
        player2.sendSystemMessage(Component.literal("Duel starting in 10 seconds!"));
    }

    public static void startMatchmakingDuel(ServerPlayer player1, ServerPlayer player2, DuelSettings settings, ArenaManager.Arena arena, Vec3 spawn1, Vec3 spawn2) {
        if (arena != null) {
            duelBackups.put(player1.getUUID(), new PlayerBackup(player1.gameMode.getGameModeForPlayer(), player1.position(), player1.level().dimension()));
            duelBackups.put(player2.getUUID(), new PlayerBackup(player2.gameMode.getGameModeForPlayer(), player2.position(), player2.level().dimension()));
            gameModeBackups.put(player1.getUUID(), player1.gameMode.getGameModeForPlayer());
            gameModeBackups.put(player2.getUUID(), player2.gameMode.getGameModeForPlayer());

            ServerLevel level = player1.server.getLevel(arena.getDimensionKey());
            if (level != null) {
                player1.teleportTo(level, spawn1.x, spawn1.y, spawn1.z, player1.getYRot(), player1.getXRot());
                player2.teleportTo(level, spawn2.x, spawn2.y, spawn2.z, player2.getYRot(), player2.getXRot());
            }
        }
        startDuel(player1, player2, settings);
        ActiveDuel duel = getDuel(player1);
        if (duel != null && arena != null) {
            duel.setArenaName(arena.name);
            duel.setSpawnPosition(player1.getUUID(), spawn1);
            duel.setSpawnPosition(player2.getUUID(), spawn2);
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

            if (duel.isCountdown()) {
                p1.teleportTo(p1.getX(), p1.getY(), p1.getZ());
                p2.teleportTo(p2.getX(), p2.getY(), p2.getZ());
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
                        duel.getBossBar().setName(Component.literal("Duel Started!"));
                        duel.getBossBar().setColor(BossEvent.BossBarColor.BLUE);
                        p1.sendSystemMessage(Component.literal("FIGHT!"));
                        p2.sendSystemMessage(Component.literal("FIGHT!"));
                    } else {
                        duel.getBossBar().setName(Component.literal("Starting in " + duel.getCountdownSeconds()));
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
                
                duel.getBossBar().setName(Component.literal(String.format("Time left %02d:%02d", remaining / 60, remaining % 60)));
                duel.getBossBar().setProgress((float)remaining / duel.getSettings().getDurationSeconds());
            }

            enforceArenaBounds(duel, p1, p2);
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

    private static void endDuel(MinecraftServer server, ActiveDuel duel, boolean draw, UUID winnerUUID) {
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team1 = scoreboard.getPlayerTeam(duel.getTeamName() + "_1");
        PlayerTeam team2 = scoreboard.getPlayerTeam(duel.getTeamName() + "_2");
        if (team1 != null) scoreboard.removePlayerTeam(team1);
        if (team2 != null) scoreboard.removePlayerTeam(team2);

        activeDuels.remove(duel.getPlayer1());
        activeDuels.remove(duel.getPlayer2());
        
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
                player1.sendSystemMessage(Component.literal("Duel ended in a draw!"));
                StatsManager.recordDraw(player1.getUUID());
            }
            if (player2 != null) {
                player2.sendSystemMessage(Component.literal("Duel ended in a draw!"));
                StatsManager.recordDraw(player2.getUUID());
            }
        } else if (winnerUUID != null) {
            UUID loserUUID = duel.getPlayer1().equals(winnerUUID) ? duel.getPlayer2() : duel.getPlayer1();
            
            StatsManager.recordWin(winnerUUID);
            StatsManager.recordLoss(loserUUID);

            MatchmakingConfigManager.PointWeights weights = MatchmakingConfigManager.getConfig().weights;
            duel.getPoints(winnerUUID).addOffense(weights.killBonus);

            ServerPlayer winner = server.getPlayerList().getPlayer(winnerUUID);
            ServerPlayer loser = server.getPlayerList().getPlayer(loserUUID);

            if (winner != null) winner.sendSystemMessage(Component.literal("You won the duel!"));
            if (loser != null) loser.sendSystemMessage(Component.literal("You lost the duel!"));
        }

        if (duel.getArenaName() != null) {
            ArenaManager.markInactive(duel.getArenaName());
        }
    }
    
    private static void restorePlayer(ServerPlayer player, ActiveDuel duel) {
        if (player == null) return;
        
        GameType prevGm = player.getUUID().equals(duel.getPlayer1()) ? duel.getPlayer1PrevGameMode() : duel.getPlayer2PrevGameMode();
        player.setGameMode(prevGm);
        gameModeBackups.remove(player.getUUID());
        PlayerBackup backup = duelBackups.remove(player.getUUID());
        if (backup != null) {
            ServerLevel level = player.server.getLevel(backup.getDimension());
            if (level != null) {
                player.teleportTo(level, backup.getPosition().x, backup.getPosition().y, backup.getPosition().z, player.getYRot(), player.getXRot());
            }
        }
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
            teleportToSpawn(duel, arena, player);
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
            teleportToSpawn(duel, arena, player);
        }
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
