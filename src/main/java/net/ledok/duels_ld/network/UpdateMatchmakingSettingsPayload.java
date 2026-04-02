package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record UpdateMatchmakingSettingsPayload(
    int oneVOneDuration,
    int oneVOneWinHp,
    int twoVTwoDuration,
    int twoVTwoWinHp,
    double offensePerDamage,
    double supportPerHeal,
    double defensePerBlocked,
    double killBonus
) implements CustomPacketPayload {
    public static final Type<UpdateMatchmakingSettingsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "update_matchmaking_settings"));

    public static final StreamCodec<FriendlyByteBuf, UpdateMatchmakingSettingsPayload> CODEC = StreamCodec.of(
        (buf, val) -> {
            buf.writeInt(val.oneVOneDuration);
            buf.writeInt(val.oneVOneWinHp);
            buf.writeInt(val.twoVTwoDuration);
            buf.writeInt(val.twoVTwoWinHp);
            buf.writeDouble(val.offensePerDamage);
            buf.writeDouble(val.supportPerHeal);
            buf.writeDouble(val.defensePerBlocked);
            buf.writeDouble(val.killBonus);
        },
        buf -> new UpdateMatchmakingSettingsPayload(
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble()
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
