package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client snapshot of every arena with readiness info, for the arena manager GUI.
 * {@code target}: 0 = refresh an already-open screen only, 1 = open the manager GUI,
 * 2 = open the wand selection screen (lets the player bind the held wand).
 */
public record ArenaListPayload(int target, List<Entry> arenas) implements CustomPacketPayload {

    public static final int REFRESH = 0;
    public static final int OPEN_GUI = 1;
    public static final int OPEN_WAND = 2;

    public record Entry(String name, String dimensionId, boolean hasBounds, int team1, int team2,
                        boolean ready1v1, boolean ready2v2, boolean active, int outside) {}

    public static final Type<ArenaListPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "arena_list"));

    public static final StreamCodec<FriendlyByteBuf, ArenaListPayload> CODEC = StreamCodec.of(
        (buf, v) -> {
            buf.writeVarInt(v.target);
            buf.writeVarInt(v.arenas.size());
            for (Entry e : v.arenas) {
                buf.writeUtf(e.name());
                buf.writeUtf(e.dimensionId());
                buf.writeBoolean(e.hasBounds());
                buf.writeVarInt(e.team1());
                buf.writeVarInt(e.team2());
                buf.writeBoolean(e.ready1v1());
                buf.writeBoolean(e.ready2v2());
                buf.writeBoolean(e.active());
                buf.writeVarInt(e.outside());
            }
        },
        buf -> {
            int target = buf.readVarInt();
            int n = buf.readVarInt();
            List<Entry> arenas = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                arenas.add(new Entry(
                    buf.readUtf(), buf.readUtf(), buf.readBoolean(),
                    buf.readVarInt(), buf.readVarInt(),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt()));
            }
            return new ArenaListPayload(target, arenas);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
