package net.ledok.duels_ld.manager;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.duels_ld.network.PartyAcceptPayload;
import net.ledok.duels_ld.network.PartyInvitePayload;
import net.ledok.duels_ld.network.RequestEloPayload;
import net.ledok.duels_ld.network.SyncEloPayload;
import net.ledok.duels_ld.network.SyncPartyPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PartyManager {
    private static final Map<UUID, Party> parties = new HashMap<>();
    private static final Map<UUID, UUID> memberToLeader = new HashMap<>();
    private static final Map<UUID, Deque<UUID>> pendingInvites = new HashMap<>(); // target -> leaders

    public static void init() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            sendPartyState(handler.getPlayer());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerId = handler.getPlayer().getUUID();
            leaveParty(playerId, server);
        });

        ServerPlayNetworking.registerGlobalReceiver(PartyInvitePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> handleInvite(context.player(), payload.targetName()));
        });
        ServerPlayNetworking.registerGlobalReceiver(PartyAcceptPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> handleAccept(context.player(), payload.leaderName()));
        });
        ServerPlayNetworking.registerGlobalReceiver(RequestEloPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                int elo1 = MMRManager.getRating1v1(player.getUUID());
                int elo2 = MMRManager.getRating2v2(player.getUUID());
                ServerPlayNetworking.send(player, new SyncEloPayload(elo1, elo2));
            });
        });
    }

    public static boolean createParty(ServerPlayer leader) {
        if (memberToLeader.containsKey(leader.getUUID())) {
            leader.sendSystemMessage(Component.translatable("duels_ld.party.already_in_party"));
            return false;
        }
        Party party = new Party(leader.getUUID());
        parties.put(leader.getUUID(), party);
        memberToLeader.put(leader.getUUID(), leader.getUUID());
        leader.sendSystemMessage(Component.translatable("duels_ld.party.created"));
        sendPartyState(leader);
        return true;
    }

    public static boolean invite(ServerPlayer leader, ServerPlayer target) {
        if (leader.getUUID().equals(target.getUUID())) {
            leader.sendSystemMessage(Component.translatable("duels_ld.party.cannot_invite_self"));
            return false;
        }
        if (!memberToLeader.containsKey(leader.getUUID())) {
            createParty(leader);
        }
        Party party = getPartyByLeader(leader.getUUID());
        if (party == null) {
            leader.sendSystemMessage(Component.translatable("duels_ld.party.not_leader"));
            return false;
        }
        if (!leader.getUUID().equals(party.leader)) {
            leader.sendSystemMessage(Component.translatable("duels_ld.party.not_leader"));
            return false;
        }
        if (party.members.size() >= 2) {
            leader.sendSystemMessage(Component.translatable("duels_ld.party.full"));
            return false;
        }
        if (memberToLeader.containsKey(target.getUUID())) {
            UUID targetLeaderId = memberToLeader.get(target.getUUID());
            Party targetParty = parties.get(targetLeaderId);
            if (targetParty == null || targetParty.members.size() > 1) {
                leader.sendSystemMessage(Component.translatable("duels_ld.party.target_in_party"));
                return false;
            }
        }
        Deque<UUID> invites = pendingInvites.computeIfAbsent(target.getUUID(), k -> new ArrayDeque<>());
        invites.remove(leader.getUUID());
        invites.addLast(leader.getUUID());
        leader.sendSystemMessage(Component.translatable("duels_ld.party.invite_sent", target.getName().getString()));
        target.sendSystemMessage(Component.translatable("duels_ld.party.invite_received", leader.getName().getString(), leader.getName().getString()));
        sendPartyState(leader);
        sendPartyState(target);
        return true;
    }

    public static boolean acceptInvite(ServerPlayer target, ServerPlayer leader) {
        Deque<UUID> invites = pendingInvites.get(target.getUUID());
        if (invites == null || !invites.contains(leader.getUUID())) {
            target.sendSystemMessage(Component.translatable("duels_ld.party.no_invite_from"));
            sendPartyState(target);
            return false;
        }
        Party party = getPartyByLeader(leader.getUUID());
        if (party == null) {
            target.sendSystemMessage(Component.translatable("duels_ld.party.no_longer_exists"));
            invites.remove(leader.getUUID());
            cleanupInviteSet(target.getUUID());
            sendPartyState(target);
            return false;
        }
        if (party.members.size() >= 2) {
            target.sendSystemMessage(Component.translatable("duels_ld.party.full"));
            invites.remove(leader.getUUID());
            cleanupInviteSet(target.getUUID());
            sendPartyState(target);
            sendPartyState(leader);
            return false;
        }
        if (memberToLeader.containsKey(target.getUUID())) {
            UUID targetLeaderId = memberToLeader.get(target.getUUID());
            Party targetParty = parties.get(targetLeaderId);
            if (targetParty != null && targetParty.members.size() > 1) {
                target.sendSystemMessage(Component.translatable("duels_ld.party.already_in_party"));
                invites.remove(leader.getUUID());
                cleanupInviteSet(target.getUUID());
                sendPartyState(target);
                return false;
            }
            if (targetParty != null && targetLeaderId.equals(target.getUUID())) {
                disbandParty(targetLeaderId, target.server);
            }
        }
        party.members.add(target.getUUID());
        memberToLeader.put(target.getUUID(), leader.getUUID());
        invites.remove(leader.getUUID());
        cleanupInviteSet(target.getUUID());
        target.sendSystemMessage(Component.translatable("duels_ld.party.joined", leader.getName().getString()));
        leader.sendSystemMessage(Component.translatable("duels_ld.party.member_joined", target.getName().getString()));
        syncPartyForLeader(target.server, leader.getUUID());
        sendPartyState(target);
        syncInviteesForLeader(target.server, leader.getUUID());
        return true;
    }

    public static void leaveParty(ServerPlayer player) {
        leaveParty(player.getUUID(), player.server);
    }

    public static void leaveParty(UUID playerId, net.minecraft.server.MinecraftServer server) {
        UUID leaderId = memberToLeader.get(playerId);
        if (leaderId == null) {
            return;
        }
        Party party = parties.get(leaderId);
        if (party == null) {
            memberToLeader.remove(playerId);
            return;
        }
        if (leaderId.equals(playerId)) {
            disbandParty(leaderId, server);
            return;
        }
        party.members.remove(playerId);
        memberToLeader.remove(playerId);
        ServerPlayer leader = server.getPlayerList().getPlayer(leaderId);
        if (leader != null) {
            leader.sendSystemMessage(Component.translatable("duels_ld.party.member_left"));
            sendPartyState(leader);
        }
        ServerPlayer leaver = server.getPlayerList().getPlayer(playerId);
        if (leaver != null) {
            leaver.sendSystemMessage(Component.translatable("duels_ld.party.left"));
            sendPartyState(leaver);
        }
    }

    public static void disbandParty(ServerPlayer leader) {
        disbandParty(leader.getUUID(), leader.server);
    }

    public static void disbandParty(UUID leaderId, net.minecraft.server.MinecraftServer server) {
        Set<UUID> membersSnapshot = new HashSet<>();
        Party existing = parties.get(leaderId);
        if (existing != null) {
            membersSnapshot.addAll(existing.members);
        }
        List<UUID> inviteTargets = getInviteTargetsForLeader(leaderId);
        Party party = parties.remove(leaderId);
        if (party == null) {
            return;
        }
        removeOutgoingInvites(leaderId);
        for (UUID member : party.members) {
            memberToLeader.remove(member);
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                player.sendSystemMessage(Component.translatable("duels_ld.party.disbanded"));
                sendPartyState(player);
            }
        }
        for (UUID memberId : membersSnapshot) {
            if (memberId.equals(leaderId)) {
                continue;
            }
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) {
                sendPartyState(member);
            }
        }
        for (UUID inviteTarget : inviteTargets) {
            ServerPlayer target = server.getPlayerList().getPlayer(inviteTarget);
            if (target != null) {
                sendPartyState(target);
            }
        }
    }

    public static Party getPartyByLeader(UUID leaderId) {
        return parties.get(leaderId);
    }

    public static UUID getLeader(UUID memberId) {
        return memberToLeader.get(memberId);
    }

    public static boolean isLeader(UUID playerId) {
        UUID leader = memberToLeader.get(playerId);
        return leader != null && leader.equals(playerId);
    }

    public static boolean isInParty(UUID playerId) {
        return memberToLeader.containsKey(playerId);
    }

    public static class Party {
        public final UUID leader;
        public final Set<UUID> members = new LinkedHashSet<>();

        public Party(UUID leader) {
            this.leader = leader;
            members.add(leader);
        }
    }

    private static void handleInvite(ServerPlayer leader, String targetName) {
        ServerPlayer target = leader.server.getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            leader.sendSystemMessage(Component.translatable("duels_ld.party.player_not_found", targetName));
            return;
        }
        invite(leader, target);
    }

    private static void handleAccept(ServerPlayer target, String leaderName) {
        if (leaderName == null || leaderName.isBlank()) {
            acceptFirstInvite(target);
            return;
        }
        ServerPlayer leader = target.server.getPlayerList().getPlayerByName(leaderName);
        if (leader == null) {
            target.sendSystemMessage(Component.translatable("duels_ld.party.player_not_found", leaderName));
            return;
        }
        acceptInvite(target, leader);
    }

    private static void acceptFirstInvite(ServerPlayer target) {
        Deque<UUID> invites = pendingInvites.get(target.getUUID());
        if (invites == null || invites.isEmpty()) {
            target.sendSystemMessage(Component.translatable("duels_ld.party.no_pending_invites"));
            sendPartyState(target);
            return;
        }
        UUID leaderId = invites.peekLast();
        ServerPlayer leader = target.server.getPlayerList().getPlayer(leaderId);
        if (leader == null) {
            invites.remove(leaderId);
            cleanupInviteSet(target.getUUID());
            target.sendSystemMessage(Component.translatable("duels_ld.party.not_available"));
            sendPartyState(target);
            return;
        }
        acceptInvite(target, leader);
    }

    private static void sendPartyState(ServerPlayer player) {
        if (player == null) {
            return;
        }
        ServerPlayNetworking.send(player, buildPartyPayload(player));
    }

    private static SyncPartyPayload buildPartyPayload(ServerPlayer player) {
        UUID playerId = player.getUUID();
        UUID leaderId = memberToLeader.get(playerId);
        List<SyncPartyPayload.Member> members = new ArrayList<>();
        if (leaderId != null) {
            Party party = parties.get(leaderId);
            if (party != null) {
                for (UUID memberId : party.members) {
                    String name = resolveName(player.server, memberId);
                    members.add(new SyncPartyPayload.Member(
                        name,
                        memberId.equals(playerId),
                        memberId.equals(leaderId),
                        false
                    ));
                }
                if (leaderId.equals(playerId)) {
                    for (UUID inviteTargetId : getInviteTargetsForLeader(leaderId)) {
                        members.add(new SyncPartyPayload.Member(
                            resolveName(player.server, inviteTargetId),
                            false,
                            false,
                            true
                        ));
                    }
                }
            }
        }
        String incomingInviteFrom = resolveIncomingInviteFrom(player.server, playerId);
        return new SyncPartyPayload(members, incomingInviteFrom);
    }

    private static String resolveIncomingInviteFrom(MinecraftServer server, UUID playerId) {
        Deque<UUID> invites = pendingInvites.get(playerId);
        if (invites == null || invites.isEmpty()) {
            return null;
        }
        UUID leaderId = invites.peekLast();
        return resolveName(server, leaderId);
    }

    private static List<UUID> getInviteTargetsForLeader(UUID leaderId) {
        List<UUID> targets = new ArrayList<>();
        for (Map.Entry<UUID, Deque<UUID>> entry : pendingInvites.entrySet()) {
            if (entry.getValue().contains(leaderId)) {
                targets.add(entry.getKey());
            }
        }
        return targets;
    }

    private static void syncPartyForLeader(MinecraftServer server, UUID leaderId) {
        Party party = parties.get(leaderId);
        if (party == null) {
            return;
        }
        for (UUID memberId : party.members) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) {
                sendPartyState(member);
            }
        }
    }

    private static void syncInviteesForLeader(MinecraftServer server, UUID leaderId) {
        for (UUID inviteTarget : getInviteTargetsForLeader(leaderId)) {
            ServerPlayer target = server.getPlayerList().getPlayer(inviteTarget);
            if (target != null) {
                sendPartyState(target);
            }
        }
    }

    private static void cleanupInviteSet(UUID targetId) {
        Deque<UUID> invites = pendingInvites.get(targetId);
        if (invites != null && invites.isEmpty()) {
            pendingInvites.remove(targetId);
        }
    }

    private static void removeOutgoingInvites(UUID leaderId) {
        for (Map.Entry<UUID, Deque<UUID>> entry : pendingInvites.entrySet()) {
            entry.getValue().remove(leaderId);
        }
        List<UUID> emptyTargets = new ArrayList<>();
        for (Map.Entry<UUID, Deque<UUID>> entry : pendingInvites.entrySet()) {
            if (entry.getValue().isEmpty()) {
                emptyTargets.add(entry.getKey());
            }
        }
        for (UUID targetId : emptyTargets) {
            pendingInvites.remove(targetId);
        }
    }

    private static String resolveName(MinecraftServer server, UUID playerId) {
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return server.getProfileCache()
            .get(playerId)
            .map(profile -> profile.getName())
            .orElse(playerId.toString().substring(0, 8));
    }

}
