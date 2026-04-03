package net.ledok.duels_ld.manager;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.duels_ld.network.PartyAcceptPayload;
import net.ledok.duels_ld.network.PartyInvitePayload;
import net.ledok.duels_ld.network.RequestEloPayload;
import net.ledok.duels_ld.network.SyncEloPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PartyManager {
    private static final Map<UUID, Party> parties = new HashMap<>();
    private static final Map<UUID, UUID> memberToLeader = new HashMap<>();
    private static final Map<UUID, Set<UUID>> pendingInvites = new HashMap<>(); // target -> leaders

    public static void init() {
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
        pendingInvites.computeIfAbsent(target.getUUID(), k -> new HashSet<>()).add(leader.getUUID());
        leader.sendSystemMessage(Component.translatable("duels_ld.party.invite_sent", target.getName().getString()));
        target.sendSystemMessage(Component.translatable("duels_ld.party.invite_received", leader.getName().getString(), leader.getName().getString()));
        return true;
    }

    public static boolean acceptInvite(ServerPlayer target, ServerPlayer leader) {
        Set<UUID> invites = pendingInvites.get(target.getUUID());
        if (invites == null || !invites.contains(leader.getUUID())) {
            target.sendSystemMessage(Component.translatable("duels_ld.party.no_invite_from"));
            return false;
        }
        Party party = getPartyByLeader(leader.getUUID());
        if (party == null) {
            target.sendSystemMessage(Component.translatable("duels_ld.party.no_longer_exists"));
            invites.remove(leader.getUUID());
            return false;
        }
        if (party.members.size() >= 2) {
            target.sendSystemMessage(Component.translatable("duels_ld.party.full"));
            invites.remove(leader.getUUID());
            return false;
        }
        if (memberToLeader.containsKey(target.getUUID())) {
            UUID targetLeaderId = memberToLeader.get(target.getUUID());
            Party targetParty = parties.get(targetLeaderId);
            if (targetParty != null && targetParty.members.size() > 1) {
                target.sendSystemMessage(Component.translatable("duels_ld.party.already_in_party"));
                invites.remove(leader.getUUID());
                return false;
            }
            if (targetParty != null && targetLeaderId.equals(target.getUUID())) {
                disbandParty(targetLeaderId, target.server);
            }
        }
        party.members.add(target.getUUID());
        memberToLeader.put(target.getUUID(), leader.getUUID());
        invites.remove(leader.getUUID());
        target.sendSystemMessage(Component.translatable("duels_ld.party.joined", leader.getName().getString()));
        leader.sendSystemMessage(Component.translatable("duels_ld.party.member_joined", target.getName().getString()));
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
        }
        ServerPlayer leaver = server.getPlayerList().getPlayer(playerId);
        if (leaver != null) {
            leaver.sendSystemMessage(Component.translatable("duels_ld.party.left"));
        }
    }

    public static void disbandParty(ServerPlayer leader) {
        disbandParty(leader.getUUID(), leader.server);
    }

    private static void disbandParty(UUID leaderId, net.minecraft.server.MinecraftServer server) {
        Party party = parties.remove(leaderId);
        if (party == null) {
            return;
        }
        for (UUID member : party.members) {
            memberToLeader.remove(member);
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                player.sendSystemMessage(Component.translatable("duels_ld.party.disbanded"));
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
        public final Set<UUID> members = new HashSet<>();

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
        Set<UUID> invites = pendingInvites.get(target.getUUID());
        if (invites == null || invites.isEmpty()) {
            target.sendSystemMessage(Component.translatable("duels_ld.party.no_pending_invites"));
            return;
        }
        UUID leaderId = invites.iterator().next();
        ServerPlayer leader = target.server.getPlayerList().getPlayer(leaderId);
        if (leader == null) {
            invites.remove(leaderId);
            target.sendSystemMessage(Component.translatable("duels_ld.party.not_available"));
            return;
        }
        acceptInvite(target, leader);
    }

}
