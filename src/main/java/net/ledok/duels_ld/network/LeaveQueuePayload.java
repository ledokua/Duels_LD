package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record LeaveQueuePayload() implements CustomPacketPayload {
    public static final Type<LeaveQueuePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "leave_queue"));

    public static final StreamCodec<FriendlyByteBuf, LeaveQueuePayload> CODEC = StreamCodec.of(
        (buf, val) -> {},
        buf -> new LeaveQueuePayload()
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
