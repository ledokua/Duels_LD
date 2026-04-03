package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PartyInvitePayload(String targetName) implements CustomPacketPayload {
    public static final Type<PartyInvitePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "party_invite"));

    public static final StreamCodec<FriendlyByteBuf, PartyInvitePayload> CODEC = StreamCodec.of(
        (buf, val) -> buf.writeUtf(val.targetName),
        buf -> new PartyInvitePayload(buf.readUtf())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
