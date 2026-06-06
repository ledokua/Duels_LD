package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server: shift+scroll on the arena wand cycles its mode. {@code forward} = scroll up. */
public record ArenaWandModePayload(boolean forward) implements CustomPacketPayload {
    public static final Type<ArenaWandModePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "arena_wand_mode"));

    public static final StreamCodec<FriendlyByteBuf, ArenaWandModePayload> CODEC = StreamCodec.of(
        (buf, v) -> buf.writeBoolean(v.forward),
        buf -> new ArenaWandModePayload(buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
