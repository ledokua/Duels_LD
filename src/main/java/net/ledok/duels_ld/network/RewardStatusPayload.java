package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server → client: the player's battle-reward status for the matchmaking screen. */
public record RewardStatusPayload(boolean enabled, long earnedToday, long dailyCap) implements CustomPacketPayload {
    public static final Type<RewardStatusPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "reward_status"));

    public static final StreamCodec<FriendlyByteBuf, RewardStatusPayload> CODEC = StreamCodec.of(
        (buf, v) -> {
            buf.writeBoolean(v.enabled);
            buf.writeVarLong(v.earnedToday);
            buf.writeVarLong(v.dailyCap);
        },
        buf -> new RewardStatusPayload(buf.readBoolean(), buf.readVarLong(), buf.readVarLong())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
