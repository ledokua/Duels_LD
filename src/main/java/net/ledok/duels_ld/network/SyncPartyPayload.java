package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> Client: full party state sync.
 * Sent whenever party membership or pending invites change.
 */
public record SyncPartyPayload(
    List<Member> members,       // current party members (empty = not in party)
    String incomingInviteFrom   // null = no pending incoming invite
) implements CustomPacketPayload {

    public static final Type<SyncPartyPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "sync_party"));

    public static final StreamCodec<FriendlyByteBuf, SyncPartyPayload> CODEC = StreamCodec.of(
        (buf, val) -> {
            buf.writeVarInt(val.members.size());
            for (Member m : val.members) {
                buf.writeUtf(m.name());
                buf.writeBoolean(m.self());
                buf.writeBoolean(m.leader());
                buf.writeBoolean(m.pending());
            }
            buf.writeBoolean(val.incomingInviteFrom != null);
            if (val.incomingInviteFrom != null) buf.writeUtf(val.incomingInviteFrom);
        },
        buf -> {
            int count = buf.readVarInt();
            List<Member> members = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                members.add(new Member(buf.readUtf(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
            }
            String incoming = buf.readBoolean() ? buf.readUtf() : null;
            return new SyncPartyPayload(members, incoming);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Member(String name, boolean self, boolean leader, boolean pending) {}
}
