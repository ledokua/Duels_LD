package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record QueueStatePayload(boolean in1v1, boolean in2v2) implements CustomPacketPayload {
    public static final Type<QueueStatePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "queue_state"));

    public static final StreamCodec<FriendlyByteBuf, QueueStatePayload> CODEC = StreamCodec.of(
        (buf, val) -> {
            buf.writeBoolean(val.in1v1);
            buf.writeBoolean(val.in2v2);
        },
        buf -> new QueueStatePayload(buf.readBoolean(), buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
