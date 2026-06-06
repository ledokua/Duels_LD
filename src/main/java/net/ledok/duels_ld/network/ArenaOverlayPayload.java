package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client snapshot of an arena's geometry so the client can draw the selection wand
 * overlay (bounding box + team-coloured spawn markers with facing). Sent when the wand is given
 * and after any edit (wand or command) to the arena being edited.
 */
public record ArenaOverlayPayload(String dimensionId, BlockPos pos1, BlockPos pos2, List<Spawn> spawns)
    implements CustomPacketPayload {

    public record Spawn(BlockPos pos, int team, float yaw) {}

    public static final Type<ArenaOverlayPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "arena_overlay"));

    public static final StreamCodec<FriendlyByteBuf, ArenaOverlayPayload> CODEC = StreamCodec.of(
        (buf, v) -> {
            buf.writeUtf(v.dimensionId);
            writeNullablePos(buf, v.pos1);
            writeNullablePos(buf, v.pos2);
            buf.writeVarInt(v.spawns.size());
            for (Spawn s : v.spawns) {
                buf.writeBlockPos(s.pos());
                buf.writeByte(s.team());
                buf.writeFloat(s.yaw());
            }
        },
        buf -> {
            String dim = buf.readUtf();
            BlockPos p1 = readNullablePos(buf);
            BlockPos p2 = readNullablePos(buf);
            int n = buf.readVarInt();
            List<Spawn> spawns = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                BlockPos pos = buf.readBlockPos();
                int team = buf.readByte();
                float yaw = buf.readFloat();
                spawns.add(new Spawn(pos, team, yaw));
            }
            return new ArenaOverlayPayload(dim, p1, p2, spawns);
        }
    );

    private static void writeNullablePos(FriendlyByteBuf buf, BlockPos pos) {
        buf.writeBoolean(pos != null);
        if (pos != null) {
            buf.writeBlockPos(pos);
        }
    }

    private static BlockPos readNullablePos(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readBlockPos() : null;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
