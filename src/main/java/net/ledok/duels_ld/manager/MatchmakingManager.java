package net.ledok.duels_ld.manager;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.duels_ld.api.DuelsApi;
import net.ledok.duels_ld.integration.EdenBridge;
import net.ledok.duels_ld.network.JoinQueuePayload;
import net.ledok.duels_ld.network.LeaveQueuePayload;
import net.ledok.duels_ld.network.OpenLobbyRequestPayload;
import net.ledok.duels_ld.network.OpenLobbyScreenPayload;
import net.ledok.duels_ld.network.PartyStatusPayload;
import net.ledok.duels_ld.network.QueueStatePayload;
import net.ledok.duels_ld.network.RequestEloPayload;
import net.ledok.duels_ld.network.SyncEloPayload;
import net.ledok.duels_ld.network.UpdateMatchmakingSettingsPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class MatchmakingManager {
    private static final ArrayDeque<QueueEntry> queue1v1 = new ArrayDeque<>();
    private static final ArrayDeque<QueueEntry> queue2v2 = new ArrayDeque<>();
    // Eden parties queued for 2v2, keyed by party id (the leader can change while queued).
    private static final ArrayDeque<PartyEntry> partyQueue2v2 = new ArrayDeque<>();
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
        // Eden parties are formed in Eden's hub. Any membership change (join, leave, kick, disband)
        // drops that party from the queue; the queued member snapshot would no longer be valid.
        EdenBridge.onMembershipChange(MatchmakingManager::onEdenPartyChanged);

        ServerPlayNetworking.registerGlobalReceiver(RequestEloPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                int elo1 = MMRManager.getRating1v1(player.getUUID());
                int elo2 = MMRManager.getRating2v2(player.getUUID());
                PlayerStats stats = StatsManager.find(player.getUUID());
                ServerPlayNetworking.send(player, new SyncEloPayload(elo1, elo2, record(stats, 1), record(stats, 2)));
                BattleRewards.sendStatus(player);
                sendPartyStatus(player);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(JoinQueuePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> joinQueue(context.player(), payload.mode()));
        });
        ServerPlayNetworking.registerGlobalReceiver(LeaveQueuePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> leaveQueue(context.player(), payload.mode()));
        });
        ServerPlayNetworking.registerGlobalReceiver(OpenLobbyRequestPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> handleOpenLobbyRequest(context.player()));
        });
        ServerPlayNetworking.registerGlobalReceiver(UpdateMatchmakingSettingsPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> updateSettings(context.player(), payload));
        });
    }

    public static void joinQueue(ServerPlayer player, int mode) {
        joinQueue(player, mode == JoinQueuePayload.MODE_2V2 ? DuelsApi.Mode.TWO_V_TWO : DuelsApi.Mode.ONE_V_ONE, true);
    }

    /**
     * Puts the player (or, for 2v2, their Eden party) in a queue. {@code announce} sends the
     * outcome to the player's chat; API callers pass false and show the result themselves.
     * Partymates are told their leader started searching either way.
     */
    public static DuelsApi.QueueResult joinQueue(ServerPlayer player, DuelsApi.Mode mode, boolean announce) {
        DuelsApi.QueueResult result = tryJoinQueue(player, mode);
        if (announce) {
            String key = switch (result) {
                case QUEUED -> mode == DuelsApi.Mode.ONE_V_ONE ? "duels_ld.matchmaking.queued_1v1"
                    : findPartyOf(player.getUUID()) != null ? "duels_ld.matchmaking.party_queued"
                    : "duels_ld.matchmaking.queued_2v2";
                case ALREADY_QUEUED -> "duels_ld.matchmaking.already_queued";
                case BUSY, IN_MATCH, PENDING_MATCH -> "duels_ld.matchmaking.cannot_queue_busy";
                case NOT_PARTY_LEADER -> "duels_ld.matchmaking.party_leader_required";
                case PARTY_WRONG_SIZE -> "duels_ld.matchmaking.party_size_required";
                case PARTY_MEMBER_UNAVAILABLE -> "duels_ld.matchmaking.party_member_unavailable";
                case DISABLED -> "duels_ld.matchmaking.mode_disabled";
            };
            if (result == DuelsApi.QueueResult.PARTY_WRONG_SIZE) {
                int size = EdenBridge.getParty(player.server, player.getUUID()).map(p -> p.members().size()).orElse(0);
                player.sendSystemMessage(Component.translatable(key, size));
            } else {
                player.sendSystemMessage(Component.translatable(key));
            }
        }
        return result;
    }

    private static DuelsApi.QueueResult tryJoinQueue(ServerPlayer player, DuelsApi.Mode mode) {
        UUID playerId = player.getUUID();
        if (DuelManager.isInDuel(player)) {
            return DuelsApi.QueueResult.IN_MATCH;
        }
        // A player already matched and waiting for a free arena must not re-enter a queue, or they
        // could be matched a second time and their pending match would collapse under the partner.
        if (containsPending(pending1v1, playerId) || containsPending(pending2v2, playerId)) {
            return DuelsApi.QueueResult.PENDING_MATCH;
        }
        if (ActivityManager.isPlayerBusy(playerId)) {
            return DuelsApi.QueueResult.BUSY;
        }
        if (!ArenaManager.hasArenaFor(mode.teamSize())) {
            return DuelsApi.QueueResult.DISABLED;
        }
        if (mode == DuelsApi.Mode.ONE_V_ONE) {
            if (containsEntry(queue1v1, playerId)) {
                return DuelsApi.QueueResult.ALREADY_QUEUED;
            }
            queue1v1.add(new QueueEntry(playerId));
            sendQueueState(player);
            return DuelsApi.QueueResult.QUEUED;
        }
        Optional<EdenBridge.Party> edenParty = EdenBridge.getParty(player.server, playerId);
        if (edenParty.isPresent()) {
            return joinPartyQueue(player, edenParty.get());
        }
        if (containsEntry(queue2v2, playerId)) {
            return DuelsApi.QueueResult.ALREADY_QUEUED;
        }
        queue2v2.add(new QueueEntry(playerId));
        sendQueueState(player);
        return DuelsApi.QueueResult.QUEUED;
    }

    private static DuelsApi.QueueResult joinPartyQueue(ServerPlayer player, EdenBridge.Party party) {
        if (findParty(party.id()) != null) {
            return DuelsApi.QueueResult.ALREADY_QUEUED;
        }
        if (!party.leader().equals(player.getUUID())) {
            return DuelsApi.QueueResult.NOT_PARTY_LEADER;
        }
        // Eden parties can be bigger than a 2v2 team; refuse rather than pick two members.
        if (party.members().size() != 2) {
            return DuelsApi.QueueResult.PARTY_WRONG_SIZE;
        }
        for (UUID member : party.members()) {
            if (member.equals(player.getUUID())) {
                continue;
            }
            ServerPlayer online = player.server.getPlayerList().getPlayer(member);
            if (online == null || ActivityManager.isPlayerBusy(member) || DuelManager.isInDuel(online)
                || containsPending(pending1v1, member) || containsPending(pending2v2, member)) {
                return DuelsApi.QueueResult.PARTY_MEMBER_UNAVAILABLE;
            }
        }
        partyQueue2v2.add(new PartyEntry(party.id(), party.members()));
        for (UUID member : party.members()) {
            removeEntry(queue2v2, member);
        }
        for (UUID member : party.members()) {
            ServerPlayer target = player.server.getPlayerList().getPlayer(member);
            if (target == null) {
                continue;
            }
            if (!member.equals(player.getUUID())) {
                target.sendSystemMessage(Component.translatable("duels_ld.matchmaking.party_leader_searching"));
            }
            sendQueueState(target);
        }
        return DuelsApi.QueueResult.QUEUED;
    }

    /**
     * Eden fires this mid-update, so the reaction is deferred to the next server task: by then the
     * party (if it still exists) reflects the change and the status sent to clients is current.
     */
    private static void onEdenPartyChanged(UUID partyId, UUID player) {
        MinecraftServer server = ArenaManager.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            PartyEntry entry = findParty(partyId);
            Set<UUID> affected = new HashSet<>();
            affected.add(player);
            if (entry != null) {
                partyQueue2v2.remove(entry);
                affected.addAll(entry.members);
            }
            EdenBridge.getParty(server, player).ifPresent(party -> affected.addAll(party.members()));
            // Whoever is left in the party the player just left still needs a fresh status.
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                if (!affected.contains(online.getUUID())
                    && EdenBridge.getParty(server, online.getUUID()).map(p -> p.id().equals(partyId)).orElse(false)) {
                    affected.add(online.getUUID());
                }
            }
            for (UUID id : affected) {
                ServerPlayer online = server.getPlayerList().getPlayer(id);
                if (online == null) {
                    continue;
                }
                if (entry != null) {
                    online.sendSystemMessage(Component.translatable("duels_ld.matchmaking.party_changed"));
                    sendQueueState(online);
                }
                sendPartyStatus(online);
            }
        });
    }

    /** Disconnect cleanup: drops the player from every queue and pending match, silently. */
    public static void leaveAllQueues(UUID playerId) {
        MinecraftServer server = ArenaManager.getServer();
        boolean removed = removeEntry(queue1v1, playerId) | removeEntry(queue2v2, playerId);
        boolean pendingRemoved = removeFromPending(playerId);
        PartyEntry partyRemoved = removePartyFromQueue(playerId);
        if (!removed && !pendingRemoved && partyRemoved == null) {
            return;
        }
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            sendQueueState(player);
        } else {
            DuelsApi.fireQueueChanged(playerId);
        }
        if (partyRemoved != null && server != null) {
            sendQueueStateToMembers(partyRemoved, server);
        }
    }

    public static void leaveAllQueues(ServerPlayer player) {
        leaveQueue(player, null, true);
    }

    public static void leaveQueue(ServerPlayer player, int mode) {
        DuelsApi.Mode apiMode = mode == LeaveQueuePayload.MODE_1V1 ? DuelsApi.Mode.ONE_V_ONE
            : mode == LeaveQueuePayload.MODE_2V2 ? DuelsApi.Mode.TWO_V_TWO
            : null;
        leaveQueue(player, apiMode, true);
    }

    /**
     * Leaves one queue, or every queue and pending match when {@code mode} is null. Leaving 2v2
     * takes the whole Eden party out (any member may cancel).
     */
    public static void leaveQueue(ServerPlayer player, @Nullable DuelsApi.Mode mode, boolean announce) {
        UUID playerId = player.getUUID();
        boolean removed = false;
        PartyEntry partyRemoved = null;
        if (mode == null || mode == DuelsApi.Mode.ONE_V_ONE) {
            removed |= removeEntry(queue1v1, playerId) | removeFromPendingList(pending1v1, playerId, true);
        }
        if (mode == null || mode == DuelsApi.Mode.TWO_V_TWO) {
            partyRemoved = removePartyFromQueue(playerId);
            removed |= removeEntry(queue2v2, playerId) | partyRemoved != null
                | removeFromPendingList(pending2v2, playerId, false);
        }
        if (removed) {
            sendQueueState(player);
            if (partyRemoved != null) {
                sendQueueStateToMembers(partyRemoved, player.server);
            }
        }
        if (announce) {
            player.sendSystemMessage(Component.translatable(!removed ? "duels_ld.matchmaking.not_in_queue"
                : mode == null ? "duels_ld.matchmaking.left_queue"
                : "duels_ld.matchmaking.left_selected_queue"));
        }
    }

    /** Snapshot for the API. */
    public static DuelsApi.QueueState queueState(UUID playerId) {
        boolean pending1 = containsPending(pending1v1, playerId);
        boolean pending2 = containsPending(pending2v2, playerId);
        QueueEntry solo1 = findEntry(queue1v1, playerId);
        QueueEntry solo2 = findEntry(queue2v2, playerId);
        PartyEntry party = findPartyOf(playerId);
        boolean in1 = solo1 != null || pending1;
        boolean in2 = solo2 != null || party != null || pending2;
        if (!in1 && !in2) {
            return DuelsApi.QueueState.NONE;
        }
        long joinedAt = Long.MAX_VALUE;
        if (solo1 != null) joinedAt = Math.min(joinedAt, solo1.joinTime);
        if (solo2 != null) joinedAt = Math.min(joinedAt, solo2.joinTime);
        if (party != null) joinedAt = Math.min(joinedAt, party.joinTime);
        boolean searching = joinedAt != Long.MAX_VALUE;
        return new DuelsApi.QueueState(in1, in2, pending1 || pending2, searching ? joinedAt : -1L,
            party != null, searching ? getCurrentRange(joinedAt) : 0);
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
        double oobDamage = Math.max(0.0, Math.min(100.0, payload.outOfBoundsDamagePercent()));
        double oobPoints = Math.max(0.0, Math.min(100.0, payload.outOfBoundsPointPenaltyPercent()));
        double extraHealth = Math.max(0.0, Math.min(1004.0, payload.extraHealth()));

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
        // Preserve sections the GUI doesn't expose (otherwise saving would reset them to defaults),
        // then apply the two out-of-bounds penalty fields that the GUI does edit.
        config.battle = current.battle;
        config.rewards = current.rewards;
        config.battle.outOfBoundsDamagePercent = oobDamage;
        config.battle.outOfBoundsPointPenaltyPercent = oobPoints;
        config.battle.extraHealth = extraHealth;

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
        List<ArenaManager.SpawnPoint> t1 = ArenaManager.pickSpawns(arena, 1, 1);
        List<ArenaManager.SpawnPoint> t2 = ArenaManager.pickSpawns(arena, 2, 1);
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
            t1.get(0),
            t2.get(0)
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
            pending1v1.poll();
            handlePendingCancelled(match, null, true);
            return;
        }
        ArenaManager.Arena arena = ArenaManager.pickArena(1);
        if (arena == null) {
            return;
        }
        List<ArenaManager.SpawnPoint> t1 = ArenaManager.pickSpawns(arena, 1, 1);
        List<ArenaManager.SpawnPoint> t2 = ArenaManager.pickSpawns(arena, 2, 1);
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
            t1.get(0),
            t2.get(0)
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
            pending2v2.poll();
            handlePendingCancelled(match, null, false);
            return;
        }
        ArenaManager.Arena arena = ArenaManager.pickArena(2);
        if (arena == null) {
            return;
        }
        List<ArenaManager.SpawnPoint> t1 = ArenaManager.pickSpawns(arena, 1, 2);
        List<ArenaManager.SpawnPoint> t2 = ArenaManager.pickSpawns(arena, 2, 2);
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
            t1.get(0),
            t1.get(1),
            t2.get(0),
            t2.get(1)
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

    /** Removes the queued party {@code playerId} belongs to (any member may cancel); returns it, or null. */
    private static PartyEntry removePartyFromQueue(UUID playerId) {
        PartyEntry entry = findPartyOf(playerId);
        if (entry != null) {
            partyQueue2v2.remove(entry);
        }
        return entry;
    }

    private static boolean removeFromPending(UUID playerId) {
        boolean removed = false;
        removed |= removeFromPendingList(pending1v1, playerId, true);
        removed |= removeFromPendingList(pending2v2, playerId, false);
        return removed;
    }

    private static boolean removeFromPendingList(ArrayDeque<List<UUID>> pending, UUID playerId, boolean is1v1) {
        boolean removed = false;
        Iterator<List<UUID>> iterator = pending.iterator();
        while (iterator.hasNext()) {
            List<UUID> match = iterator.next();
            if (match.contains(playerId)) {
                iterator.remove();
                removed = true;
                handlePendingCancelled(match, playerId, is1v1);
            }
        }
        return removed;
    }

    /**
     * Tells the surviving participants of a dissolved pending match what happened and resets their
     * client queue UI (they are no longer queued server-side, so without this their screen shows a
     * "searching" timer forever). 1v1 survivors who are still available are put back at the front of
     * the queue so they keep their matchmaking priority; 2v2 groups are told to queue again, since
     * the party/solo composition of the pending group is no longer known.
     */
    private static void handlePendingCancelled(List<UUID> match, UUID removedPlayer, boolean is1v1) {
        MinecraftServer server = ArenaManager.getServer();
        if (server == null) {
            return;
        }
        for (UUID id : match) {
            if (id.equals(removedPlayer)) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                continue;
            }
            boolean requeued = false;
            if (is1v1 && !ActivityManager.isPlayerBusy(id) && !DuelManager.isInDuel(player)
                && !containsEntry(queue1v1, id)) {
                queue1v1.addFirst(new QueueEntry(id));
                requeued = true;
            }
            player.sendSystemMessage(Component.translatable(requeued
                ? "duels_ld.matchmaking.pending_requeued"
                : "duels_ld.matchmaking.cancel_unavailable"));
            sendQueueState(player);
        }
    }

    private static void cleanPartyQueue(MinecraftServer server) {
        // Membership changes are handled by the Eden listener; this only drops parties with a
        // member who went offline or got busy elsewhere.
        Iterator<PartyEntry> iterator = partyQueue2v2.iterator();
        while (iterator.hasNext()) {
            PartyEntry entry = iterator.next();
            boolean allOnline = true;
            for (UUID member : entry.members) {
                if (server.getPlayerList().getPlayer(member) == null || ActivityManager.isPlayerBusy(member)) {
                    allOnline = false;
                    break;
                }
            }
            if (!allOnline) {
                iterator.remove();
                sendQueueStateToMembers(entry, server);
            }
        }
    }

    private static boolean tryMatchParties(MinecraftServer server) {
        if (partyQueue2v2.size() < 2) {
            return false;
        }
        PartyEntry seeker = partyQueue2v2.peek();
        if (seeker == null) {
            return false;
        }
        PartyEntry best = findBestPartyOpponent(seeker, partyQueue2v2);
        if (best == null) {
            return false;
        }
        partyQueue2v2.remove(seeker);
        partyQueue2v2.remove(best);
        return start2v2Match(server, seeker.members, best.members);
    }

    private static boolean tryMatchPartyWithSolos(MinecraftServer server) {
        if (partyQueue2v2.isEmpty() || queue2v2.size() < 2) {
            return false;
        }
        PartyEntry partyEntry = partyQueue2v2.peek();
        if (partyEntry == null) {
            return false;
        }
        QueueEntry solo1Entry = queue2v2.peek();
        QueueEntry solo2Entry = secondEntry(queue2v2);
        if (solo1Entry == null || solo2Entry == null) {
            return false;
        }
        List<UUID> partyTeam = partyEntry.members;
        List<UUID> soloTeam = List.of(solo1Entry.playerId, solo2Entry.playerId);

        int partyRating = averageRating2v2(partyTeam);
        int soloRating = averageRating2v2(soloTeam);
        int partyRange = getCurrentRange(partyEntry.joinTime);
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

        List<ArenaManager.SpawnPoint> t1 = ArenaManager.pickSpawns(arena, 1, 2);
        List<ArenaManager.SpawnPoint> t2 = ArenaManager.pickSpawns(arena, 2, 2);
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
            t1.get(0),
            t1.get(1),
            t2.get(0),
            t2.get(1)
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

    private static PartyEntry findBestPartyOpponent(PartyEntry seeker, ArrayDeque<PartyEntry> queue) {
        int seekerRating = averageRating2v2(seeker.members);
        int seekerRange = getCurrentRange(seeker.joinTime);
        PartyEntry best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (PartyEntry entry : queue) {
            if (entry.partyId.equals(seeker.partyId)) {
                continue;
            }
            int rating = averageRating2v2(entry.members);
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
        // max(1, ...) guards against a hand-edited config with mmrRangeIncreaseSeconds <= 0, which
        // would otherwise divide by zero and crash every server tick while anyone is queued.
        long steps = Math.max(0, elapsedMs / (Math.max(1, config.mmrRangeIncreaseSeconds) * 1000L));
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

    private static boolean containsEntry(ArrayDeque<QueueEntry> queue, UUID playerId) {
        for (QueueEntry entry : queue) {
            if (entry.playerId.equals(playerId)) {
                return true;
            }
        }
        return false;
    }

    private static QueueEntry findEntry(ArrayDeque<QueueEntry> queue, UUID playerId) {
        for (QueueEntry entry : queue) {
            if (entry.playerId.equals(playerId)) {
                return entry;
            }
        }
        return null;
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

    /** A queued Eden party: the id it is keyed by and the two members it had when it queued. */
    private static class PartyEntry {
        private final UUID partyId;
        private final List<UUID> members;
        private final long joinTime;

        private PartyEntry(UUID partyId, List<UUID> members) {
            this.partyId = partyId;
            this.members = List.copyOf(members);
            this.joinTime = System.currentTimeMillis();
        }
    }

    private static PartyEntry findParty(UUID partyId) {
        for (PartyEntry entry : partyQueue2v2) {
            if (entry.partyId.equals(partyId)) {
                return entry;
            }
        }
        return null;
    }

    private static PartyEntry findPartyOf(UUID playerId) {
        for (PartyEntry entry : partyQueue2v2) {
            if (entry.members.contains(playerId)) {
                return entry;
            }
        }
        return null;
    }

    private static int[] record(PlayerStats stats, int teamSize) {
        return stats == null ? new int[3]
            : new int[] {stats.getWins(teamSize), stats.getLosses(teamSize), stats.getDraws(teamSize)};
    }

    /** Tells the client which Eden party the player is in, for the matchmaking screen. */
    public static void sendPartyStatus(ServerPlayer player) {
        PartyStatusPayload payload = EdenBridge.getParty(player.server, player.getUUID())
            .map(party -> new PartyStatusPayload(true, party.name(), party.members().size(),
                party.leader().equals(player.getUUID())))
            .orElseGet(() -> new PartyStatusPayload(EdenBridge.isAvailable(), "", 0, false));
        ServerPlayNetworking.send(player, payload);
    }

    /** Syncs the player's queue flags to the client and tells API listeners. */
    private static void sendQueueState(ServerPlayer player) {
        UUID playerId = player.getUUID();
        ServerPlayNetworking.send(player, new QueueStatePayload(isQueued1v1(playerId), isQueued2v2(playerId)));
        DuelsApi.fireQueueChanged(playerId);
    }

    private static boolean isQueued1v1(UUID playerId) {
        return containsEntry(queue1v1, playerId) || containsPending(pending1v1, playerId);
    }

    private static boolean isQueued2v2(UUID playerId) {
        return containsEntry(queue2v2, playerId)
            || findPartyOf(playerId) != null
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

    private static void sendQueueStateToMembers(PartyEntry entry, MinecraftServer server) {
        for (UUID memberId : entry.members) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) {
                sendQueueState(member);
            }
        }
    }
}
