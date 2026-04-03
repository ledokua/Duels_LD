package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RequestEloPayload() implements CustomPacketPayload {
    public static final Type<RequestEloPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "request_elo"));

    public static final StreamCodec<FriendlyByteBuf, RequestEloPayload> CODEC = StreamCodec.of(
        (buf, val) -> {},
        buf -> new RequestEloPayload()
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
