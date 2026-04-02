package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record JoinQueuePayload(int mode) implements CustomPacketPayload {
    public static final int MODE_1V1 = 1;
    public static final int MODE_2V2 = 2;

    public static final Type<JoinQueuePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "join_queue"));

    public static final StreamCodec<FriendlyByteBuf, JoinQueuePayload> CODEC = StreamCodec.of(
        (buf, val) -> buf.writeInt(val.mode),
        buf -> new JoinQueuePayload(buf.readInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
