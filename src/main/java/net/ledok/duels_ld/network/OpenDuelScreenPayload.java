package net.ledok.duels_ld.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.ledok.duels_ld.DuelsLdMod;

public record OpenDuelScreenPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenDuelScreenPayload> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "open_duel_screen"));
    
    public static final StreamCodec<FriendlyByteBuf, OpenDuelScreenPayload> CODEC = StreamCodec.of(
        (buf, val) -> {},
        buf -> new OpenDuelScreenPayload()
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
