package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server arena management action from the GUI. {@code action} is one of:
 * create, delete, clone, tp, wand, pos1, pos2, addspawn. {@code arg} carries the clone target
 * name or the spawn team ("1"/"2"); empty otherwise. Server validates op permission.
 */
public record ArenaActionPayload(String action, String name, String arg) implements CustomPacketPayload {
    public static final Type<ArenaActionPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "arena_action"));

    public static final StreamCodec<FriendlyByteBuf, ArenaActionPayload> CODEC = StreamCodec.of(
        (buf, v) -> {
            buf.writeUtf(v.action);
            buf.writeUtf(v.name);
            buf.writeUtf(v.arg);
        },
        buf -> new ArenaActionPayload(buf.readUtf(), buf.readUtf(), buf.readUtf())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
