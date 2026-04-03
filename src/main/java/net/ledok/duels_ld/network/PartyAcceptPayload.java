package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PartyAcceptPayload(String leaderName) implements CustomPacketPayload {
    public static final Type<PartyAcceptPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "party_accept"));

    public static final StreamCodec<FriendlyByteBuf, PartyAcceptPayload> CODEC = StreamCodec.of(
        (buf, val) -> buf.writeUtf(val.leaderName),
        buf -> new PartyAcceptPayload(buf.readUtf())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
