package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SyncEloPayload(int elo1v1, int elo2v2) implements CustomPacketPayload {
    public static final Type<SyncEloPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "sync_elo"));

    public static final StreamCodec<FriendlyByteBuf, SyncEloPayload> CODEC = StreamCodec.of(
        (buf, val) -> {
            buf.writeInt(val.elo1v1);
            buf.writeInt(val.elo2v2);
        },
        buf -> new SyncEloPayload(buf.readInt(), buf.readInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
