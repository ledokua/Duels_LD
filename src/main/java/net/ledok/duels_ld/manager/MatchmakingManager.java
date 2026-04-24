package net.ledok.duels_ld.manager;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.duels_ld.network.JoinQueuePayload;
import net.ledok.duels_ld.network.LeaveQueuePayload;
import net.ledok.duels_ld.network.OpenLobbyRequestPayload;
import net.ledok.duels_ld.network.OpenLobbyScreenPayload;
import net.ledok.duels_ld.network.QueueStatePayload;
import net.ledok.duels_ld.network.UpdateMatchmakingSettingsPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MatchmakingManager {
    private static final ArrayDeque<QueueEntry> queue1v1 = new ArrayDeque<>();
    private static final ArrayDeque<QueueEntry> queue2v2 = new ArrayDeque<>();
    private static final ArrayDeque<QueueEntry> partyQueue2v2 = new ArrayDeque<>(); // leader ids
    private static final ArrayDeque<List<UUID>> pending1v1 = new ArrayDeque<>();
    private static final ArrayDeque<List<UUID>> pending2v2 = new ArrayDeque<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(MatchmakingManager::onServerTick);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerId = handler.getPlayer().getUUID();
            leaveAllQueues(playerId);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            sendQueueState(handler.getPlayer());
        });

        ServerPlayNetworking.registerGlobalReceiver(JoinQueuePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> joinQueue(context.player(), payload.mode()));
        });
        ServerPlayNetworking.registerGlobalReceiver(LeaveQueuePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> leaveAllQueues(context.player()));
        });
        ServerPlayNetworking.registerGlobalReceiver(OpenLobbyRequestPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> handleOpenLobbyRequest(context.player()));
        });
        ServerPlayNetworking.registerGlobalReceiver(UpdateMatchmakingSettingsPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> updateSettings(context.player(), payload));
        });
    }

    public static void joinQueue(ServerPlayer player, int mode) {
        if (ActivityManager.isPlayerBusy(player.getUUID()) || DuelManager.isInDuel(player)) {
            player.sendSystemMessage(Component.translatable("duels_ld.matchmaking.cannot_queue_busy"));
            return;
        }
        boolean updated = false;
        if (mode == JoinQueuePayload.MODE_1V1) {
            if (!containsEntry(queue1v1, player.getUUID())) {
                queue1v1.add(new QueueEntry(player.getUUID()));
                player.sendSystemMessage(Component.translatable("duels_ld.matchmaking.queued_1v1"));
                updated = true;
            }
        } else if (mode == JoinQueuePayload.MODE_2V2) {
            if (PartyManager.isInParty(player.getUUID())) {
                UUID leader = PartyManager.getLeader(player.getUUID());
                PartyManager.Party party = PartyManager.getPartyByLeader(leader);
                if (party == null || party.members.size() != 2) {
                    player.sendSystemMessage(Component.translatable("duels_ld.matchmaking.party_size_required"));
                    return;
                }
                if (!PartyManager.isLeader(player.getUUID())) {
                    player.sendSystemMessage(Component.translatable("duels_ld.matchmaking.party_leader_required"));
                    return;
                }
                if (!containsEntry(partyQueue2v2, leader)) {
                    partyQueue2v2.add(new QueueEntry(leader));
                    for (UUID member : party.members) {
                        removeEntry(queue2v2, member);
                    }
                    player.sendSystemMessage(Component.translatable("duels_ld.matchmaking.party_queued"));
                    for (UUID member : party.members) {
                        if (member.equals(player.getUUID())) {
                            continue;
                        }
                        ServerPlayer target = player.server.getPlayerList().getPlayer(member);
                        if (target != null) {
                            target.sendSystemMessage(Component.translatable("duels_ld.matchmaking.party_leader_searching"));
                            sendQueueState(target);
                        }
                    }
                    sendQueueState(player);
                }
                return;
            }
            if (!containsEntry(queue2v2, player.getUUID())) {
                queue2v2.add(new QueueEntry(player.getUUID()));
                player.sendSystemMessage(Component.translatable("duels_ld.matchmaking.queued_2v2"));
                updated = true;
            }
        }
        if (updated) {
            sendQueueState(player);
        }
    }

    public static void leaveAllQueues(UUID playerId) {
        boolean removed = removeEntry(queue1v1, playerId) | removeEntry(queue2v2, playerId);
        boolean pendingRemoved = removeFromPending(playerId);
        boolean partyRemoved = removePartyFromQueue(playerId);
        if (removed || pendingRemoved || partyRemoved) {
            MinecraftServer server = ArenaManager.getServer();
            if (server != null) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    sendQueueState(player);
                }
            }
        }
    }

    public static void leaveAllQueues(ServerPlayer player) {
        boolean removed = removeEntry(queue1v1, player.getUUID()) | removeEntry(queue2v2, player.getUUID());
        boolean pendingRemoved = removeFromPending(player.getUUID());
        boolean partyRemoved = removePartyFromQueue(player.getUUID());
        if (removed || pendingRemoved || partyRemoved) {
            player.sendSystemMessage(Component.translatable("duels_ld.matchmaking.left_queue"));
            sendQueueState(player);
        } else {
            player.sendSystemMessage(Component.translatable("duels_ld.matchmaking.not_in_queue"));
        }
    }

    public static int getQueued1v1Count() {
        return queue1v1.size() + pending1v1.size() * 2;
    }

    public static int getQueued2v2Count() {
        return queue2v2.size() + partyQueue2v2.size() * 2 + pending2v2.size() * 4;
    }

    public static int getQueuedTotalCount() {
        return getQueued1v1Count() + getQueued2v2Count();
    }

    private static void handleOpenLobbyRequest(ServerPlayer player) {
        if (ActivityManager.isPlayerBusy(player.getUUID()) || DuelManager.isInDuel(player)) {
            player.sendSystemMessage(Component.translatable("duels_ld.matchmaking.cannot_open_busy"));
            return;
        }
        ServerPlayNetworking.send(player, new OpenLobbyScreenPayload());
    }

    private static void updateSettings(ServerPlayer player, UpdateMatchmakingSettingsPayload payload) {
        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.translatable("duels_ld.matchmaking.no_permission_update"));
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

        MatchmakingConfigManager.MatchmakingConfig current = MatchmakingConfigManager.getConfig();
        MatchmakingConfigManager.MatchmakingConfig config = new MatchmakingConfigManager.MatchmakingConfig();
        config.oneVOne = new MatchmakingConfigManager.MatchSettings(oneTime, oneHp);
        config.twoVTwo = new MatchmakingConfigManager.MatchSettings(twoTime, twoHp);
        config.weights = new MatchmakingConfigManager.PointWeights(
            off,
            sup,
            def,
            kill
        );
        config.elo = current.elo;
        config.mmrRangeStart = current.mmrRangeStart;
        config.mmrRangeIncrease = current.mmrRangeIncrease;
        config.mmrRangeIncreaseSeconds = current.mmrRangeIncreaseSeconds;
        config.mmrRangeMax = current.mmrRangeMax;

        MatchmakingConfigManager.updateConfig(config);
        player.sendSystemMessage(Component.translatable("duels_ld.matchmaking.settings_updated"));
    }

    private static void onServerTick(MinecraftServer server) {
        processPending2v2(server);
        processPending1v1(server);
        processQueue2v2(server);
        processQueue1v1(server);
    }

    private static void processQueue1v1(MinecraftServer server) {
        cleanQueue(queue1v1, server);
        if (queue1v1.size() < 2) {
            return;
        }
        QueueEntry seeker = queue1v1.peek();
        if (seeker == null) {
            return;
        }
        QueueEntry best = findBest1v1Opponent(seeker, queue1v1);
        if (best == null) {
            return;
        }
        queue1v1.remove(seeker);
        queue1v1.remove(best);

        ServerPlayer p1 = server.getPlayerList().getPlayer(seeker.playerId);
        ServerPlayer p2 = server.getPlayerList().getPlayer(best.playerId);
        if (p1 == null || p2 == null) {
            return;
        }

        ArenaManager.Arena arena = ArenaManager.pickArena(1);
        if (arena == null) {
            List<UUID> match = List.of(p1.getUUID(), p2.getUUID());
            pending1v1.add(match);
            removeFromOtherQueues(p1.getUUID());
            removeFromOtherQueues(p2.getUUID());
            notifyPending(p1, Component.translatable("duels_ld.matchmaking.pending_1v1"));
            notifyPending(p2, Component.translatable("duels_ld.matchmaking.pending_1v1"));
            sendQueueState(p1);
            sendQueueState(p2);
            return;
        }
        List<net.minecraft.core.BlockPos> t1 = ArenaManager.pickSpawns(arena, 1, 1);
        List<net.minecraft.core.BlockPos> t2 = ArenaManager.pickSpawns(arena, 2, 1);
        if (t1.isEmpty() || t2.isEmpty()) {
            List<UUID> match = List.of(p1.getUUID(), p2.getUUID());
            pending1v1.add(match);
            removeFromOtherQueues(p1.getUUID());
            removeFromOtherQueues(p2.getUUID());
            notifyPending(p1, Component.translatable("duels_ld.matchmaking.pending_1v1"));
            notifyPending(p2, Component.translatable("duels_ld.matchmaking.pending_1v1"));
            sendQueueState(p1);
            sendQueueState(p2);
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
        sendQueueState(p1);
        sendQueueState(p2);
    }

    private static void processQueue2v2(MinecraftServer server) {
        cleanQueue(queue2v2, server);
        cleanPartyQueue(server);
        if (!partyQueue2v2.isEmpty()) {
            if (tryMatchParties(server)) {
                return;
            }
            if (tryMatchPartyWithSolos(server)) {
                return;
            }
        }
        if (queue2v2.size() < 4) {
            return;
        }
        List<QueueEntry> entries = new ArrayList<>(queue2v2);
        entries.sort((a, b) -> Long.compare(a.joinTime, b.joinTime));
        QueueEntry a1 = entries.get(0);
        QueueEntry a2 = entries.get(1);
        QueueEntry b1 = entries.get(2);
        QueueEntry b2 = entries.get(3);
        matchSoloTeams(server, a1, a2, b1, b2);
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
            notifyCancel(match, Component.translatable("duels_ld.matchmaking.cancel_unavailable"));
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
        sendQueueState(p1);
        sendQueueState(p2);
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
            notifyCancel(match, Component.translatable("duels_ld.matchmaking.cancel_unavailable"));
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

        DuelSettings settings = new DuelSettings();
        MatchmakingConfigManager.MatchmakingConfig config = MatchmakingConfigManager.getConfig();
        settings.setDurationSeconds(config.twoVTwo.durationSeconds);
        settings.setWinHpPercentage(config.twoVTwo.winHpPercentage);
        DuelManager.startMatchmakingDuel2v2(server, p1, p2, p3, p4, settings,
            arena,
            t1.get(0).getCenter(),
            t1.get(1).getCenter(),
            t2.get(0).getCenter(),
            t2.get(1).getCenter()
        );
        sendQueueState(p1);
        sendQueueState(p2);
        sendQueueState(p3);
        sendQueueState(p4);
    }

    private static void cleanQueue(ArrayDeque<QueueEntry> queue, MinecraftServer server) {
        Iterator<QueueEntry> iterator = queue.iterator();
        Set<UUID> seen = new HashSet<>();
        while (iterator.hasNext()) {
            QueueEntry entry = iterator.next();
            if (!seen.add(entry.playerId)) {
                iterator.remove();
                ServerPlayer player = server.getPlayerList().getPlayer(entry.playerId);
                if (player != null) {
                    sendQueueState(player);
                }
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.playerId);
            if (player == null || ActivityManager.isPlayerBusy(entry.playerId)) {
                iterator.remove();
                if (player != null) {
                    sendQueueState(player);
                }
            }
        }
    }

    private static void removeFromOtherQueues(UUID playerId) {
        removeEntry(queue1v1, playerId);
        removeEntry(queue2v2, playerId);
    }

    private static boolean removePartyFromQueue(UUID playerId) {
        UUID leader = PartyManager.getLeader(playerId);
        if (leader == null) {
            return false;
        }
        return removeEntry(partyQueue2v2, leader);
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

    private static void cleanPartyQueue(MinecraftServer server) {
        Iterator<QueueEntry> iterator = partyQueue2v2.iterator();
        while (iterator.hasNext()) {
            QueueEntry entry = iterator.next();
            UUID leader = entry.playerId;
            PartyManager.Party party = PartyManager.getPartyByLeader(leader);
            if (party == null || party.members.size() != 2) {
                iterator.remove();
                ServerPlayer leaderPlayer = server.getPlayerList().getPlayer(leader);
                if (leaderPlayer != null) {
                    sendQueueState(leaderPlayer);
                }
                continue;
            }
            boolean allOnline = true;
            for (UUID member : party.members) {
                if (server.getPlayerList().getPlayer(member) == null || ActivityManager.isPlayerBusy(member)) {
                    allOnline = false;
                    break;
                }
            }
            if (!allOnline) {
                iterator.remove();
                ServerPlayer leaderPlayer = server.getPlayerList().getPlayer(leader);
                if (leaderPlayer != null) {
                    sendQueueState(leaderPlayer);
                }
            }
        }
    }

    private static boolean tryMatchParties(MinecraftServer server) {
        if (partyQueue2v2.size() < 2) {
            return false;
        }
        QueueEntry seeker = partyQueue2v2.peek();
        if (seeker == null) {
            return false;
        }
        QueueEntry best = findBestPartyOpponent(seeker, partyQueue2v2);
        if (best == null) {
            return false;
        }
        partyQueue2v2.remove(seeker);
        partyQueue2v2.remove(best);

        PartyManager.Party partyA = PartyManager.getPartyByLeader(seeker.playerId);
        PartyManager.Party partyB = PartyManager.getPartyByLeader(best.playerId);
        if (partyA == null || partyB == null || partyA.members.size() != 2 || partyB.members.size() != 2) {
            return false;
        }

        List<UUID> team1 = new ArrayList<>(partyA.members);
        List<UUID> team2 = new ArrayList<>(partyB.members);
        return start2v2Match(server, team1, team2);
    }

    private static boolean tryMatchPartyWithSolos(MinecraftServer server) {
        if (partyQueue2v2.isEmpty() || queue2v2.size() < 2) {
            return false;
        }
        QueueEntry leaderEntry = partyQueue2v2.peek();
        if (leaderEntry == null) {
            return false;
        }
        PartyManager.Party party = PartyManager.getPartyByLeader(leaderEntry.playerId);
        if (party == null || party.members.size() != 2) {
            partyQueue2v2.poll();
            return false;
        }
        QueueEntry solo1Entry = queue2v2.peek();
        QueueEntry solo2Entry = secondEntry(queue2v2);
        if (solo1Entry == null || solo2Entry == null) {
            return false;
        }
        List<UUID> partyTeam = new ArrayList<>(party.members);
        List<UUID> soloTeam = List.of(solo1Entry.playerId, solo2Entry.playerId);

        int partyRating = averageRating2v2(partyTeam);
        int soloRating = averageRating2v2(soloTeam);
        int partyRange = getCurrentRange(leaderEntry.joinTime);
        int soloRange = Math.min(getCurrentRange(solo1Entry.joinTime), getCurrentRange(solo2Entry.joinTime));
        int diff = Math.abs(partyRating - soloRating);
        if (diff > partyRange || diff > soloRange) {
            return false;
        }

        partyQueue2v2.poll();
        queue2v2.remove(solo1Entry);
        queue2v2.remove(solo2Entry);
        return start2v2Match(server, partyTeam, soloTeam);
    }

    private static boolean start2v2Match(MinecraftServer server, List<UUID> team1, List<UUID> team2) {
        ServerPlayer p1 = server.getPlayerList().getPlayer(team1.get(0));
        ServerPlayer p2 = server.getPlayerList().getPlayer(team1.get(1));
        ServerPlayer p3 = server.getPlayerList().getPlayer(team2.get(0));
        ServerPlayer p4 = server.getPlayerList().getPlayer(team2.get(1));
        if (p1 == null || p2 == null || p3 == null || p4 == null) {
            return false;
        }
        if (ActivityManager.isPlayerBusy(p1.getUUID()) || ActivityManager.isPlayerBusy(p2.getUUID())
            || ActivityManager.isPlayerBusy(p3.getUUID()) || ActivityManager.isPlayerBusy(p4.getUUID())) {
            return false;
        }

        ArenaManager.Arena arena = ArenaManager.pickArena(2);
        if (arena == null) {
            List<UUID> match = List.of(p1.getUUID(), p2.getUUID(), p3.getUUID(), p4.getUUID());
            pending2v2.add(match);
            notifyPending(p1, Component.translatable("duels_ld.matchmaking.pending_2v2"));
            notifyPending(p2, Component.translatable("duels_ld.matchmaking.pending_2v2"));
            notifyPending(p3, Component.translatable("duels_ld.matchmaking.pending_2v2"));
            notifyPending(p4, Component.translatable("duels_ld.matchmaking.pending_2v2"));
            sendQueueState(p1);
            sendQueueState(p2);
            sendQueueState(p3);
            sendQueueState(p4);
            return true;
        }

        List<net.minecraft.core.BlockPos> t1 = ArenaManager.pickSpawns(arena, 1, 2);
        List<net.minecraft.core.BlockPos> t2 = ArenaManager.pickSpawns(arena, 2, 2);
        if (t1.size() < 2 || t2.size() < 2) {
            return false;
        }

        ArenaManager.markActive(arena.name);
        DuelSettings settings = new DuelSettings();
        MatchmakingConfigManager.MatchmakingConfig config = MatchmakingConfigManager.getConfig();
        settings.setDurationSeconds(config.twoVTwo.durationSeconds);
        settings.setWinHpPercentage(config.twoVTwo.winHpPercentage);
        DuelManager.startMatchmakingDuel2v2(server, p1, p2, p3, p4, settings,
            arena,
            t1.get(0).getCenter(),
            t1.get(1).getCenter(),
            t2.get(0).getCenter(),
            t2.get(1).getCenter()
        );
        sendQueueState(p1);
        sendQueueState(p2);
        sendQueueState(p3);
        sendQueueState(p4);
        return true;
    }

    private static boolean matchSoloTeams(MinecraftServer server, QueueEntry a1, QueueEntry a2, QueueEntry b1, QueueEntry b2) {
        List<UUID> team1 = List.of(a1.playerId, a2.playerId);
        List<UUID> team2 = List.of(b1.playerId, b2.playerId);
        int team1Rating = averageRating2v2(team1);
        int team2Rating = averageRating2v2(team2);

        int range1 = Math.min(getCurrentRange(a1.joinTime), getCurrentRange(a2.joinTime));
        int range2 = Math.min(getCurrentRange(b1.joinTime), getCurrentRange(b2.joinTime));
        int diff = Math.abs(team1Rating - team2Rating);
        if (diff > range1 || diff > range2) {
            return false;
        }
        queue2v2.remove(a1);
        queue2v2.remove(a2);
        queue2v2.remove(b1);
        queue2v2.remove(b2);
        return start2v2Match(server, team1, team2);
    }

    private static QueueEntry findBest1v1Opponent(QueueEntry seeker, ArrayDeque<QueueEntry> queue) {
        int seekerRating = MMRManager.getRating1v1(seeker.playerId);
        int seekerRange = getCurrentRange(seeker.joinTime);
        QueueEntry best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (QueueEntry entry : queue) {
            if (entry.playerId.equals(seeker.playerId)) {
                continue;
            }
            int rating = MMRManager.getRating1v1(entry.playerId);
            int entryRange = getCurrentRange(entry.joinTime);
            int diff = Math.abs(seekerRating - rating);
            if (diff <= seekerRange && diff <= entryRange) {
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = entry;
                }
            }
        }
        return best;
    }

    private static QueueEntry findBestPartyOpponent(QueueEntry seeker, ArrayDeque<QueueEntry> queue) {
        PartyManager.Party seekerParty = PartyManager.getPartyByLeader(seeker.playerId);
        if (seekerParty == null || seekerParty.members.size() != 2) {
            return null;
        }
        int seekerRating = averageRating2v2(new ArrayList<>(seekerParty.members));
        int seekerRange = getCurrentRange(seeker.joinTime);
        QueueEntry best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (QueueEntry entry : queue) {
            if (entry.playerId.equals(seeker.playerId)) {
                continue;
            }
            PartyManager.Party party = PartyManager.getPartyByLeader(entry.playerId);
            if (party == null || party.members.size() != 2) {
                continue;
            }
            int rating = averageRating2v2(new ArrayList<>(party.members));
            int entryRange = getCurrentRange(entry.joinTime);
            int diff = Math.abs(seekerRating - rating);
            if (diff <= seekerRange && diff <= entryRange) {
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = entry;
                }
            }
        }
        return best;
    }

    private static int getCurrentRange(long joinTime) {
        MatchmakingConfigManager.MatchmakingConfig config = MatchmakingConfigManager.getConfig();
        long elapsedMs = System.currentTimeMillis() - joinTime;
        long steps = Math.max(0, elapsedMs / (config.mmrRangeIncreaseSeconds * 1000L));
        long range = config.mmrRangeStart + steps * config.mmrRangeIncrease;
        if (config.mmrRangeMax > 0) {
            range = Math.min(range, config.mmrRangeMax);
        }
        return (int) range;
    }

    private static int averageRating2v2(List<UUID> team) {
        int sum = 0;
        for (UUID id : team) {
            sum += MMRManager.getRating2v2(id);
        }
        return (int) Math.round(sum / (double) team.size());
    }

    private static QueueEntry secondEntry(ArrayDeque<QueueEntry> queue) {
        Iterator<QueueEntry> it = queue.iterator();
        if (!it.hasNext()) return null;
        it.next();
        return it.hasNext() ? it.next() : null;
    }

    private static void notifyPending(ServerPlayer player, Component message) {
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    private static void notifyCancel(List<UUID> players, Component message) {
        MinecraftServer srv = ArenaManager.getServer();
        if (srv == null) {
            return;
        }
        for (UUID id : players) {
            ServerPlayer player = srv.getPlayerList().getPlayer(id);
            if (player != null) {
                player.sendSystemMessage(message);
            }
        }
    }

    private static boolean containsEntry(ArrayDeque<QueueEntry> queue, UUID playerId) {
        for (QueueEntry entry : queue) {
            if (entry.playerId.equals(playerId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean removeEntry(ArrayDeque<QueueEntry> queue, UUID playerId) {
        Iterator<QueueEntry> iterator = queue.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().playerId.equals(playerId)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static class QueueEntry {
        private final UUID playerId;
        private final long joinTime;

        private QueueEntry(UUID playerId) {
            this.playerId = playerId;
            this.joinTime = System.currentTimeMillis();
        }
    }

    private static void sendQueueState(ServerPlayer player) {
        UUID playerId = player.getUUID();
        ServerPlayNetworking.send(player, new QueueStatePayload(isQueued1v1(playerId), isQueued2v2(playerId)));
    }

    private static boolean isQueued1v1(UUID playerId) {
        return containsEntry(queue1v1, playerId) || containsPending(pending1v1, playerId);
    }

    private static boolean isQueued2v2(UUID playerId) {
        UUID leader = PartyManager.getLeader(playerId);
        return containsEntry(queue2v2, playerId)
            || (leader != null && containsEntry(partyQueue2v2, leader))
            || containsPending(pending2v2, playerId);
    }

    private static boolean containsPending(ArrayDeque<List<UUID>> pending, UUID playerId) {
        for (List<UUID> match : pending) {
            if (match.contains(playerId)) {
                return true;
            }
        }
        return false;
    }
}
