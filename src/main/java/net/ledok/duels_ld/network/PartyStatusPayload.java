package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The player's Eden_LD party as the matchmaking screen shows it. Parties are formed in Eden's hub,
 * so this is display-only. {@code edenPresent} false means the server runs without Eden (2v2 is
 * solo-only); an empty {@code name} means the player is not in a party.
 */
public record PartyStatusPayload(boolean edenPresent, String name, int size, boolean leader) implements CustomPacketPayload {
    public static final Type<PartyStatusPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "party_status"));

    public static final StreamCodec<FriendlyByteBuf, PartyStatusPayload> CODEC = StreamCodec.of(
        (buf, val) -> {
            buf.writeBoolean(val.edenPresent);
            buf.writeUtf(val.name);
            buf.writeVarInt(val.size);
            buf.writeBoolean(val.leader);
        },
        buf -> new PartyStatusPayload(buf.readBoolean(), buf.readUtf(), buf.readVarInt(), buf.readBoolean())
    );

    public boolean inParty() {
        return !name.isEmpty();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
