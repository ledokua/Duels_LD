package net.ledok.duels_ld.manager;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.duels_ld.network.JoinQueuePayload;
import net.ledok.duels_ld.network.LeaveQueuePayload;
import net.ledok.duels_ld.network.UpdateMatchmakingSettingsPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MatchmakingManager {
    private static final ArrayDeque<UUID> queue1v1 = new ArrayDeque<>();
    private static final ArrayDeque<UUID> queue2v2 = new ArrayDeque<>();
    private static final ArrayDeque<List<UUID>> pending1v1 = new ArrayDeque<>();
    private static final ArrayDeque<List<UUID>> pending2v2 = new ArrayDeque<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(MatchmakingManager::onServerTick);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerId = handler.getPlayer().getUUID();
            leaveAllQueues(playerId);
        });

        ServerPlayNetworking.registerGlobalReceiver(JoinQueuePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> joinQueue(context.player(), payload.mode()));
        });
        ServerPlayNetworking.registerGlobalReceiver(LeaveQueuePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> leaveAllQueues(context.player()));
        });
        ServerPlayNetworking.registerGlobalReceiver(UpdateMatchmakingSettingsPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> updateSettings(context.player(), payload));
        });
    }

    public static void joinQueue(ServerPlayer player, int mode) {
        if (ActivityManager.isPlayerBusy(player.getUUID()) || DuelManager.isInDuel(player) || BattleManager.isPlayerInBattle(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You cannot queue while busy or in a match."));
            return;
        }

        if (mode == JoinQueuePayload.MODE_1V1) {
            if (!queue1v1.contains(player.getUUID())) {
                queue1v1.add(player.getUUID());
                player.sendSystemMessage(Component.literal("Queued for 1v1."));
            }
        } else if (mode == JoinQueuePayload.MODE_2V2) {
            if (!queue2v2.contains(player.getUUID())) {
                queue2v2.add(player.getUUID());
                player.sendSystemMessage(Component.literal("Queued for 2v2."));
            }
        }
    }

    public static void leaveAllQueues(UUID playerId) {
        boolean removed = queue1v1.remove(playerId) | queue2v2.remove(playerId);
        boolean pendingRemoved = removeFromPending(playerId);
        if (removed) {
            // Best-effort message if player online
            // We don't have a ServerPlayer here, so no message.
        }
    }

    public static void leaveAllQueues(ServerPlayer player) {
        boolean removed = queue1v1.remove(player.getUUID()) | queue2v2.remove(player.getUUID());
        boolean pendingRemoved = removeFromPending(player.getUUID());
        if (removed || pendingRemoved) {
            player.sendSystemMessage(Component.literal("You left all queues."));
        } else {
            player.sendSystemMessage(Component.literal("You are not in any queue."));
        }
    }

    private static void updateSettings(ServerPlayer player, UpdateMatchmakingSettingsPayload payload) {
        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal("You do not have permission to update matchmaking settings."));
            return;
        }

        int oneTime = Math.max(10, payload.oneVOneDuration());
        int twoTime = Math.max(10, payload.twoVTwoDuration());
        int oneHp = Math.min(100, Math.max(0, payload.oneVOneWinHp()));
        int twoHp = Math.min(100, Math.max(0, payload.twoVTwoWinHp()));
        double off = Math.max(0.0, payload.offensePerDamage());
        double sup = Math.max(0.0, payload.supportPerHeal());
        double def = Math.max(0.0, payload.defensePerBlocked());
        double kill = Math.max(0.0, payload.killBonus());

        MatchmakingConfigManager.MatchmakingConfig config = new MatchmakingConfigManager.MatchmakingConfig();
        config.oneVOne = new MatchmakingConfigManager.MatchSettings(oneTime, oneHp);
        config.twoVTwo = new MatchmakingConfigManager.MatchSettings(twoTime, twoHp);
        config.weights = new MatchmakingConfigManager.PointWeights(
            off,
            sup,
            def,
            kill
        );

        MatchmakingConfigManager.updateConfig(config);
        player.sendSystemMessage(Component.literal("Matchmaking settings updated."));
    }

    private static void onServerTick(MinecraftServer server) {
        processPending2v2(server);
        processPending1v1(server);
        processQueue2v2(server);
        processQueue1v1(server);
    }

    private static void processQueue1v1(MinecraftServer server) {
        cleanQueue(queue1v1, server);
        while (queue1v1.size() >= 2) {
            ServerPlayer p1 = server.getPlayerList().getPlayer(queue1v1.poll());
            ServerPlayer p2 = server.getPlayerList().getPlayer(queue1v1.poll());
            if (p1 == null || p2 == null) {
                continue;
            }
            if (ActivityManager.isPlayerBusy(p1.getUUID()) || ActivityManager.isPlayerBusy(p2.getUUID())) {
                continue;
            }
            ArenaManager.Arena arena = ArenaManager.pickArena(1);
            if (arena == null) {
                List<UUID> match = List.of(p1.getUUID(), p2.getUUID());
                pending1v1.add(match);
                removeFromOtherQueues(p1.getUUID());
                removeFromOtherQueues(p2.getUUID());
                notifyPending(p1, "1v1 match found. Waiting for a free arena.");
                notifyPending(p2, "1v1 match found. Waiting for a free arena.");
                return;
            }
            List<net.minecraft.core.BlockPos> t1 = ArenaManager.pickSpawns(arena, 1, 1);
            List<net.minecraft.core.BlockPos> t2 = ArenaManager.pickSpawns(arena, 2, 1);
            if (t1.isEmpty() || t2.isEmpty()) {
                List<UUID> match = List.of(p1.getUUID(), p2.getUUID());
                pending1v1.add(match);
                removeFromOtherQueues(p1.getUUID());
                removeFromOtherQueues(p2.getUUID());
                notifyPending(p1, "1v1 match found. Waiting for a free arena.");
                notifyPending(p2, "1v1 match found. Waiting for a free arena.");
                return;
            }

            removeFromOtherQueues(p1.getUUID());
            removeFromOtherQueues(p2.getUUID());
            ArenaManager.markActive(arena.name);

            DuelSettings settings = new DuelSettings();
            MatchmakingConfigManager.MatchmakingConfig config = MatchmakingConfigManager.getConfig();
            settings.setDurationSeconds(config.oneVOne.durationSeconds);
            settings.setWinHpPercentage(config.oneVOne.winHpPercentage);
            DuelManager.startMatchmakingDuel(p1, p2, settings,
                arena,
                t1.get(0).getCenter(),
                t2.get(0).getCenter()
            );
        }
    }

    private static void processQueue2v2(MinecraftServer server) {
        cleanQueue(queue2v2, server);
        while (queue2v2.size() >= 4) {
            ServerPlayer p1 = server.getPlayerList().getPlayer(queue2v2.poll());
            ServerPlayer p2 = server.getPlayerList().getPlayer(queue2v2.poll());
            ServerPlayer p3 = server.getPlayerList().getPlayer(queue2v2.poll());
            ServerPlayer p4 = server.getPlayerList().getPlayer(queue2v2.poll());
            if (p1 == null || p2 == null || p3 == null || p4 == null) {
                continue;
            }
            if (ActivityManager.isPlayerBusy(p1.getUUID()) || ActivityManager.isPlayerBusy(p2.getUUID())
                || ActivityManager.isPlayerBusy(p3.getUUID()) || ActivityManager.isPlayerBusy(p4.getUUID())) {
                continue;
            }

            ArenaManager.Arena arena = ArenaManager.pickArena(2);
            if (arena == null) {
                List<UUID> match = List.of(p1.getUUID(), p2.getUUID(), p3.getUUID(), p4.getUUID());
                pending2v2.add(match);
                removeFromOtherQueues(p1.getUUID());
                removeFromOtherQueues(p2.getUUID());
                removeFromOtherQueues(p3.getUUID());
                removeFromOtherQueues(p4.getUUID());
                notifyPending(p1, "2v2 match found. Waiting for a free arena.");
                notifyPending(p2, "2v2 match found. Waiting for a free arena.");
                notifyPending(p3, "2v2 match found. Waiting for a free arena.");
                notifyPending(p4, "2v2 match found. Waiting for a free arena.");
                return;
            }
            List<net.minecraft.core.BlockPos> t1 = ArenaManager.pickSpawns(arena, 1, 2);
            List<net.minecraft.core.BlockPos> t2 = ArenaManager.pickSpawns(arena, 2, 2);
            if (t1.size() < 2 || t2.size() < 2) {
                List<UUID> match = List.of(p1.getUUID(), p2.getUUID(), p3.getUUID(), p4.getUUID());
                pending2v2.add(match);
                removeFromOtherQueues(p1.getUUID());
                removeFromOtherQueues(p2.getUUID());
                removeFromOtherQueues(p3.getUUID());
                removeFromOtherQueues(p4.getUUID());
                notifyPending(p1, "2v2 match found. Waiting for a free arena.");
                notifyPending(p2, "2v2 match found. Waiting for a free arena.");
                notifyPending(p3, "2v2 match found. Waiting for a free arena.");
                notifyPending(p4, "2v2 match found. Waiting for a free arena.");
                return;
            }

            removeFromOtherQueues(p1.getUUID());
            removeFromOtherQueues(p2.getUUID());
            removeFromOtherQueues(p3.getUUID());
            removeFromOtherQueues(p4.getUUID());
            ArenaManager.markActive(arena.name);

            BattleSettings settings = new BattleSettings();
            MatchmakingConfigManager.MatchmakingConfig config = MatchmakingConfigManager.getConfig();
            settings.setDurationSeconds(config.twoVTwo.durationSeconds);
            BattleManager.startMatchmakingBattle(server, p1, p2, p3, p4, settings,
                arena,
                t1.get(0).getCenter(),
                t1.get(1).getCenter(),
                t2.get(0).getCenter(),
                t2.get(1).getCenter()
            );
        }
    }

    private static void cleanQueue(ArrayDeque<UUID> queue, MinecraftServer server) {
        Iterator<UUID> iterator = queue.iterator();
        Set<UUID> seen = new HashSet<>();
        while (iterator.hasNext()) {
            UUID id = iterator.next();
            if (!seen.add(id)) {
                iterator.remove();
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || ActivityManager.isPlayerBusy(id)) {
                iterator.remove();
            }
        }
    }

    private static void removeFromOtherQueues(UUID playerId) {
        queue1v1.remove(playerId);
        queue2v2.remove(playerId);
    }

    private static boolean removeFromPending(UUID playerId) {
        boolean removed = false;
        removed |= removeFromPendingList(pending1v1, playerId);
        removed |= removeFromPendingList(pending2v2, playerId);
        return removed;
    }

    private static boolean removeFromPendingList(ArrayDeque<List<UUID>> pending, UUID playerId) {
        boolean removed = false;
        Iterator<List<UUID>> iterator = pending.iterator();
        while (iterator.hasNext()) {
            List<UUID> match = iterator.next();
            if (match.contains(playerId)) {
                iterator.remove();
                removed = true;
            }
        }
        return removed;
    }

    private static void processPending1v1(MinecraftServer server) {
        if (pending1v1.isEmpty()) {
            return;
        }
        List<UUID> match = pending1v1.peek();
        if (match == null || match.size() < 2) {
            pending1v1.poll();
            return;
        }
        ServerPlayer p1 = server.getPlayerList().getPlayer(match.get(0));
        ServerPlayer p2 = server.getPlayerList().getPlayer(match.get(1));
        if (p1 == null || p2 == null || ActivityManager.isPlayerBusy(p1.getUUID()) || ActivityManager.isPlayerBusy(p2.getUUID())) {
            notifyCancel(match, "Match cancelled due to player unavailable.");
            pending1v1.poll();
            return;
        }
        ArenaManager.Arena arena = ArenaManager.pickArena(1);
        if (arena == null) {
            return;
        }
        List<net.minecraft.core.BlockPos> t1 = ArenaManager.pickSpawns(arena, 1, 1);
        List<net.minecraft.core.BlockPos> t2 = ArenaManager.pickSpawns(arena, 2, 1);
        if (t1.isEmpty() || t2.isEmpty()) {
            return;
        }
        pending1v1.poll();
        ArenaManager.markActive(arena.name);

        DuelSettings settings = new DuelSettings();
        MatchmakingConfigManager.MatchmakingConfig config = MatchmakingConfigManager.getConfig();
        settings.setDurationSeconds(config.oneVOne.durationSeconds);
        settings.setWinHpPercentage(config.oneVOne.winHpPercentage);
        DuelManager.startMatchmakingDuel(p1, p2, settings,
            arena,
            t1.get(0).getCenter(),
            t2.get(0).getCenter()
        );
    }

    private static void processPending2v2(MinecraftServer server) {
        if (pending2v2.isEmpty()) {
            return;
        }
        List<UUID> match = pending2v2.peek();
        if (match == null || match.size() < 4) {
            pending2v2.poll();
            return;
        }
        ServerPlayer p1 = server.getPlayerList().getPlayer(match.get(0));
        ServerPlayer p2 = server.getPlayerList().getPlayer(match.get(1));
        ServerPlayer p3 = server.getPlayerList().getPlayer(match.get(2));
        ServerPlayer p4 = server.getPlayerList().getPlayer(match.get(3));
        if (p1 == null || p2 == null || p3 == null || p4 == null
            || ActivityManager.isPlayerBusy(p1.getUUID()) || ActivityManager.isPlayerBusy(p2.getUUID())
            || ActivityManager.isPlayerBusy(p3.getUUID()) || ActivityManager.isPlayerBusy(p4.getUUID())) {
            notifyCancel(match, "Match cancelled due to player unavailable.");
            pending2v2.poll();
            return;
        }
        ArenaManager.Arena arena = ArenaManager.pickArena(2);
        if (arena == null) {
            return;
        }
        List<net.minecraft.core.BlockPos> t1 = ArenaManager.pickSpawns(arena, 1, 2);
        List<net.minecraft.core.BlockPos> t2 = ArenaManager.pickSpawns(arena, 2, 2);
        if (t1.size() < 2 || t2.size() < 2) {
            return;
        }
        pending2v2.poll();
        ArenaManager.markActive(arena.name);

        BattleSettings settings = new BattleSettings();
        MatchmakingConfigManager.MatchmakingConfig config = MatchmakingConfigManager.getConfig();
        settings.setDurationSeconds(config.twoVTwo.durationSeconds);
        BattleManager.startMatchmakingBattle(server, p1, p2, p3, p4, settings,
            arena,
            t1.get(0).getCenter(),
            t1.get(1).getCenter(),
            t2.get(0).getCenter(),
            t2.get(1).getCenter()
        );
    }

    private static void notifyPending(ServerPlayer player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }

    private static void notifyCancel(List<UUID> players, String message) {
        MinecraftServer srv = ArenaManager.getServer();
        if (srv == null) {
            return;
        }
        for (UUID id : players) {
            ServerPlayer player = srv.getPlayerList().getPlayer(id);
            if (player != null) {
                player.sendSystemMessage(Component.literal(message));
            }
        }
    }
}
