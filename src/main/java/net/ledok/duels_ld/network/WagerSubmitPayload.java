package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server: "post" on the stake screen open as {@code containerId}, with the coins and comment typed. */
public record WagerSubmitPayload(int containerId, long coins, String comment) implements CustomPacketPayload {
    public static final Type<WagerSubmitPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "wager_submit"));

    public static final StreamCodec<FriendlyByteBuf, WagerSubmitPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.containerId);
            buf.writeVarLong(p.coins);
            buf.writeUtf(p.comment, 256);
        },
        buf -> new WagerSubmitPayload(buf.readVarInt(), buf.readVarLong(), buf.readUtf(256)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
