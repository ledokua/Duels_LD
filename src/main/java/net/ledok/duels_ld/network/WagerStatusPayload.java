package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: how an action from a wager screen ended. {@code containerId} names the stake
 * screen it belongs to, or -1 for the review screen. {@code ok} closes the review screen.
 */
public record WagerStatusPayload(int containerId, boolean ok, Component message) implements CustomPacketPayload {
    public static final Type<WagerStatusPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "wager_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WagerStatusPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.containerId);
            buf.writeBoolean(p.ok);
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, p.message);
        },
        buf -> new WagerStatusPayload(buf.readVarInt(), buf.readBoolean(), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
