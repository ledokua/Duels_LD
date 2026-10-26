package net.ledok.duels_ld.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.ledok.duels_ld.manager.ArenaWand;
import net.ledok.duels_ld.network.ArenaOverlayPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Draws the arena selection overlay while holding a duel arena wand: a purple bounding box for the
 * region and red/blue marker boxes (with a facing line) for team 1 / team 2 spawns. Through-wall,
 * client-side only. Fed by {@link ArenaOverlayPayload} snapshots from the server.
 */
public final class ArenaOverlayRenderer {
    private static final float[] BOUNDS_COLOR = {0.66f, 0.55f, 0.91f}; // accent purple
    private static final float[] TEAM1_COLOR = {0.91f, 0.38f, 0.29f};  // red
    private static final float[] TEAM2_COLOR = {0.30f, 0.55f, 0.95f};  // blue
    private static final float ALPHA = 0.95f;

    private static ArenaOverlayPayload snapshot;

    private ArenaOverlayRenderer() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ArenaOverlayRenderer::render);
    }

    public static void setSnapshot(ArenaOverlayPayload payload) {
        snapshot = payload;
    }

    /** Cleared on disconnect so a previous server's boxes never render in the next world. */
    public static void clearSnapshot() {
        snapshot = null;
    }

    private static void render(WorldRenderContext context) {
        ArenaOverlayPayload snap = snapshot;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (snap == null || player == null || mc.level == null) {
            return;
        }
        if (!holdingWand(player)) {
            return;
        }
        if (!mc.level.dimension().location().toString().equals(snap.dimensionId())) {
            return;
        }

        Vec3 cam = context.camera().getPosition();
        PoseStack poseStack = context.matrixStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = poseStack.last().pose();

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.lineWidth(2.0F);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance()
            .begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        if (snap.pos1() != null && snap.pos2() != null) {
            BlockPos a = snap.pos1();
            BlockPos b = snap.pos2();
            addBox(buffer, matrix,
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1,
                BOUNDS_COLOR);
        }

        for (ArenaOverlayPayload.Spawn spawn : snap.spawns()) {
            float[] color = spawn.team() == 1 ? TEAM1_COLOR : TEAM2_COLOR;
            BlockPos p = spawn.pos();
            addBox(buffer, matrix, p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, color);
            addFacingLine(buffer, matrix, p, spawn.yaw(), color);
        }

        MeshData mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }

        RenderSystem.lineWidth(1.0F);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static boolean holdingWand(LocalPlayer player) {
        return ArenaWand.wandArena(player.getMainHandItem()) != null
            || ArenaWand.wandArena(player.getOffhandItem()) != null;
    }

    private static void addFacingLine(BufferBuilder buffer, Matrix4f matrix, BlockPos pos, float yaw, float[] color) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        double rad = Math.toRadians(yaw);
        double dx = -Math.sin(rad);
        double dz = Math.cos(rad);
        double len = 1.5;
        edge(buffer, matrix, (float) cx, (float) cy, (float) cz,
            (float) (cx + dx * len), (float) cy, (float) (cz + dz * len), color);
    }

    private static void addBox(BufferBuilder buffer, Matrix4f matrix,
                               float x0, float y0, float z0, float x1, float y1, float z1, float[] color) {
        // bottom
        edge(buffer, matrix, x0, y0, z0, x1, y0, z0, color);
        edge(buffer, matrix, x1, y0, z0, x1, y0, z1, color);
        edge(buffer, matrix, x1, y0, z1, x0, y0, z1, color);
        edge(buffer, matrix, x0, y0, z1, x0, y0, z0, color);
        // top
        edge(buffer, matrix, x0, y1, z0, x1, y1, z0, color);
        edge(buffer, matrix, x1, y1, z0, x1, y1, z1, color);
        edge(buffer, matrix, x1, y1, z1, x0, y1, z1, color);
        edge(buffer, matrix, x0, y1, z1, x0, y1, z0, color);
        // verticals
        edge(buffer, matrix, x0, y0, z0, x0, y1, z0, color);
        edge(buffer, matrix, x1, y0, z0, x1, y1, z0, color);
        edge(buffer, matrix, x1, y0, z1, x1, y1, z1, color);
        edge(buffer, matrix, x0, y0, z1, x0, y1, z1, color);
    }

    private static void edge(BufferBuilder buffer, Matrix4f matrix,
                             float ax, float ay, float az, float bx, float by, float bz, float[] color) {
        buffer.addVertex(matrix, ax, ay, az).setColor(color[0], color[1], color[2], ALPHA);
        buffer.addVertex(matrix, bx, by, bz).setColor(color[0], color[1], color[2], ALPHA);
    }
}
