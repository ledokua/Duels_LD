package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server: the author accepts or declines the reviewed offer ({@code dontAskAgain}: skip the loss warning from now on). */
public record WagerReviewActionPayload(int offerId, boolean accept, boolean dontAskAgain) implements CustomPacketPayload {
    public static final Type<WagerReviewActionPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "wager_review_action"));

    public static final StreamCodec<FriendlyByteBuf, WagerReviewActionPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.offerId);
            buf.writeBoolean(p.accept);
            buf.writeBoolean(p.dontAskAgain);
        },
        buf -> new WagerReviewActionPayload(buf.readVarInt(), buf.readBoolean(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
