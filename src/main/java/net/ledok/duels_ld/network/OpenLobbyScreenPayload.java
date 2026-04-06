package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OpenLobbyScreenPayload() implements CustomPacketPayload {
    public static final Type<OpenLobbyScreenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "open_lobby_screen"));

    public static final StreamCodec<FriendlyByteBuf, OpenLobbyScreenPayload> CODEC = StreamCodec.of(
        (buf, val) -> {},
        buf -> new OpenLobbyScreenPayload()
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
