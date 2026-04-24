package net.ledok.duels_ld.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.duels_ld.network.JoinQueuePayload;
import net.ledok.duels_ld.network.LeaveQueuePayload;
import net.ledok.duels_ld.network.PartyAcceptPayload;
import net.ledok.duels_ld.network.PartyInvitePayload;
import net.ledok.duels_ld.network.RequestEloPayload;
import net.ledok.duels_ld.network.SyncPartyPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class MatchmakingScreen extends Screen {

    // ── Layout ────────────────────────────────────────────────────────────
    private static final int W  = 240;   // screen width  (2× = 480px on screen)
    private static final int P  = 12;    // horizontal padding
    private static final int IW = W - P * 2; // inner width = 216

    // Fixed Y zones (from screen top)
    private static final int Y_TITLE      = 0;
    private static final int TITLE_H      = 18;
    private static final int Y_RATING     = TITLE_H + 8;
    private static final int CARD_H       = 46;
    private static final int CARD_W       = (IW - 6) / 2;   // two cards with 6px gap
    private static final int Y_QUEUE      = Y_RATING + 10 + CARD_H + 8;
    private static final int BTN_H        = 22;
    private static final int Y_LEAVE_ALL  = Y_QUEUE + 10 + BTN_H + 5;
    private static final int Y_PARTY      = Y_LEAVE_ALL + 10 + 8;

    // ── Colors (from design tokens) ───────────────────────────────────────
    private static final int C_BG           = 0xFF0f1120;
    private static final int C_SURFACE      = 0xFF141728;
    private static final int C_SURFACE_DEEP = 0xFF0c0e1a;
    private static final int C_BORDER       = 0xFF1e2240;
    private static final int C_TEXT         = 0xFFc8ccd8;
    private static final int C_MUTED        = 0xFF5a6080;
    private static final int C_DIM          = 0xFF363a58;

    // Blue (1v1)
    private static final int C_BLUE         = 0xFF38bdf8;
    private static final int C_BLUE_BG      = 0xFF061828;
    private static final int C_BLUE_BDR     = 0xFF0e3a5a;

    // Teal (2v2)
    private static final int C_TEAL         = 0xFF2dd4bf;
    private static final int C_TEAL_BG      = 0xFF061a18;
    private static final int C_TEAL_BDR     = 0xFF0e4040;

    // Red (leave / danger)
    private static final int C_RED_BG       = 0xFF1e0808;
    private static final int C_RED_BDR      = 0xFF5a1e1e;

    // Amber (pending)
    private static final int C_AMBER        = 0xFFfbbf24;
    private static final int C_AMBER_BG     = 0xFF1e1000;
    private static final int C_AMBER_BDR    = 0xFF5a3800;

    private static final int C_GOLD         = 0xFFf59e0b;
    private static final int C_TEAL_NAME    = 0xFF2dd4bf;
    private static final int C_GREEN        = 0xFF4ade80;
    private static final int C_GREEN_BG     = 0xFF0d2a10;
    private static final int C_GREEN_BDR    = 0xFF2a6030;

    // ── Persistent client state ───────────────────────────────────────────
    private static long queuedAt1v1 = -1;
    private static long queuedAt2v2 = -1;
    private static int elo1v1 = 0;
    private static int elo2v2 = 0;
    // Party state — updated via SyncPartyPayload
    private static List<SyncPartyPayload.Member> partyMembers = new ArrayList<>();
    private static String incomingInvite = null;

    // ── Instance state ────────────────────────────────────────────────────
    private long queue1v1Start = -1;
    private long queue2v2Start = -1;

    // ── Widgets ───────────────────────────────────────────────────────────
    private Button btn1v1;
    private Button btn2v2;
    private Button btnLeaveAll;
    private EditBox inviteBox;
    private EditBox acceptBox;
    private Button btnSendInvite;
    private Button btnAcceptInvite;
    private Button btnDeclineInvite;

    public MatchmakingScreen() {
        super(Component.translatable("duels_ld.screen.matchmaking.title"));
    }

    // ── Init ──────────────────────────────────────────────────────────────
    @Override
    protected void init() {
        ClientPlayNetworking.send(new RequestEloPayload());

        queue1v1Start = queuedAt1v1;
        queue2v2Start = queuedAt2v2;

        int x = (width - W) / 2;
        int y = (height - screenHeight()) / 2;

        // ── Zone 2: Queue buttons ──────────────────────────────────────────
        int btnW = CARD_W;
        btn1v1 = addRenderableWidget(Button.builder(
            Component.empty(), // label set in updateButtons
            b -> handle1v1Click()
        ).bounds(x + P, y + Y_QUEUE + 10, btnW, BTN_H).build());

        btn2v2 = addRenderableWidget(Button.builder(
            Component.empty(),
            b -> handle2v2Click()
        ).bounds(x + P + btnW + 6, y + Y_QUEUE + 10, btnW, BTN_H).build());

        btnLeaveAll = addRenderableWidget(Button.builder(
            Component.translatable("duels_ld.screen.matchmaking.leave_all"),
            b -> handleLeaveAll()
        ).bounds(x + W / 2 - 50, y + Y_LEAVE_ALL, 100, 12).build());

        // ── Zone 3: Party ─────────────────────────────────────────────────
        int partyY = y + Y_PARTY + 16 + partyMembersHeight() + 4 + 1 + 7;

        inviteBox = new EditBox(font,
            x + 2 + P, partyY, IW - 50, 14,
            Component.translatable("duels_ld.screen.matchmaking.invite_placeholder"));
        inviteBox.setMaxLength(32);
        addRenderableWidget(inviteBox);

        btnSendInvite = addRenderableWidget(Button.builder(
            Component.translatable("duels_ld.screen.matchmaking.send"),
            b -> {
                String name = inviteBox.getValue().trim();
                if (!name.isEmpty()) {
                    ClientPlayNetworking.send(new PartyInvitePayload(name));
                    inviteBox.setValue("");
                }
            }
        ).bounds(x + P + IW - 45, partyY, 42, 14).build());

        // Incoming invite accept/decline — positioned below invite row
        int incY = partyY + 14 + 5;
        acceptBox = new EditBox(font, x + P, incY, 0, 0, Component.empty()); // hidden placeholder
        acceptBox.visible = false;
        addRenderableWidget(acceptBox);

        btnAcceptInvite = addRenderableWidget(Button.builder(
            Component.translatable("duels_ld.screen.matchmaking.accept"),
            b -> {
                if (incomingInvite != null) {
                    ClientPlayNetworking.send(new PartyAcceptPayload(incomingInvite));
                }
            }
        ).bounds(x + P + IW - 88, incY, 40, 12).build());

        btnDeclineInvite = addRenderableWidget(Button.builder(
            Component.translatable("duels_ld.screen.matchmaking.decline"),
            b -> incomingInvite = null // decline = just ignore — no dedicated payload currently
        ).bounds(x + P + IW - 44, incY, 44, 12).build());

        updateButtons();
    }

    // ── Button actions ────────────────────────────────────────────────────
    private void handle1v1Click() {
        if (queue1v1Start >= 0) {
            ClientPlayNetworking.send(new LeaveQueuePayload(LeaveQueuePayload.MODE_1V1));
            // state will be corrected by server QueueStatePayload response
        } else {
            ClientPlayNetworking.send(new JoinQueuePayload(JoinQueuePayload.MODE_1V1));
        }
    }

    private void handle2v2Click() {
        if (queue2v2Start >= 0) {
            ClientPlayNetworking.send(new LeaveQueuePayload(LeaveQueuePayload.MODE_2V2));
        } else {
            ClientPlayNetworking.send(new JoinQueuePayload(JoinQueuePayload.MODE_2V2));
        }
    }

    private void handleLeaveAll() {
        ClientPlayNetworking.send(new LeaveQueuePayload(LeaveQueuePayload.MODE_ALL));
    }

    // ── Button state ──────────────────────────────────────────────────────
    private void updateButtons() {
        boolean q1 = queue1v1Start >= 0;
        boolean q2 = queue2v2Start >= 0;

        btn1v1.setMessage(q1
            ? Component.translatable("duels_ld.screen.matchmaking.leave_queue")
            : Component.translatable("duels_ld.screen.matchmaking.queue_1v1"));

        btn2v2.setMessage(q2
            ? Component.translatable("duels_ld.screen.matchmaking.leave_queue")
            : Component.translatable("duels_ld.screen.matchmaking.queue_2v2"));

        btnLeaveAll.visible = q1 || q2;
        btnLeaveAll.active = q1 || q2;

        boolean hasIncoming = incomingInvite != null;
        btnAcceptInvite.visible = hasIncoming;
        btnAcceptInvite.active = hasIncoming;
        btnDeclineInvite.visible = hasIncoming;
        btnDeclineInvite.active = hasIncoming;
    }

    // ── Render ────────────────────────────────────────────────────────────
    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        int x = (width - W) / 2;
        int y = (height - screenHeight()) / 2;
        long now = System.currentTimeMillis();

        renderBackground(g, x, y);
        renderTitle(g, x, y);
        renderRatingZone(g, x, y, now);
        renderQueueZone(g, x, y);
        renderPartyZone(g, x, y);

        super.render(g, mx, my, dt);
    }

    private void renderBackground(GuiGraphics g, int x, int y) {
        int h = screenHeight();
        g.fill(x, y, x + W, y + h, C_BG);
        drawBorder(g, x, y, W, h, C_BORDER);
    }

    private void renderTitle(GuiGraphics g, int x, int y) {
        g.fill(x + 1, y + 1, x + W - 1, y + TITLE_H - 1, C_SURFACE);
        drawBorder(g, x + 1, y + 1, W - 2, TITLE_H - 2, C_BORDER);
        int tw = font.width(title);
        g.drawString(font, title, x + (W - tw) / 2, y + 5, C_MUTED, false);
    }

    private void renderRatingZone(GuiGraphics g, int x, int y, long now) {
        smallLabel(g, "My Rating", x + P, y + Y_RATING);

        boolean q1 = queue1v1Start >= 0;
        boolean q2 = queue2v2Start >= 0;
        int cardY = y + Y_RATING + 10;

        // 1v1 card
        int c1x = x + P;
        renderEloCard(g, c1x, cardY, CARD_W, CARD_H,
            q1, C_BLUE, C_BLUE_BG, C_BLUE_BDR,
            "1V1", elo1v1,
            q1 ? elapsedSecs(queue1v1Start, now) : 0);

        // 2v2 card
        int c2x = x + P + CARD_W + 6;
        renderEloCard(g, c2x, cardY, CARD_W, CARD_H,
            q2, C_TEAL, C_TEAL_BG, C_TEAL_BDR,
            "2V2", elo2v2,
            q2 ? elapsedSecs(queue2v2Start, now) : 0);
    }

    private void renderEloCard(GuiGraphics g, int x, int y, int w, int h,
                               boolean active, int accent, int accentBg, int accentBdr,
                               String mode, int elo, long timerSec) {
        // Background
        g.fill(x, y, x + w, y + h, active ? accentBg : C_SURFACE_DEEP);
        // Border — 2px simulated with two fills
        g.fill(x, y, x + w, y + 1, active ? accentBdr : C_BORDER);       // top
        g.fill(x, y + h - 1, x + w, y + h, active ? accentBdr : C_BORDER); // bottom
        g.fill(x, y, x + 1, y + h, active ? accentBdr : C_BORDER);        // left
        g.fill(x + w - 1, y, x + w, y + h, active ? accentBdr : C_BORDER); // right
        // Active top accent bar (2px)
        if (active) {
            g.fill(x, y, x + w, y + 2, accent);
        }

        // Mode label
        int labelColor = active ? accent : C_MUTED;
        smallLabel(g, mode, x + 4, y + 5);
        // Override color of the label we just drew — redraw with correct color
        g.pose().pushPose();
        g.pose().translate(x + 4, y + 5, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(font, mode, 0, 0, labelColor, false);
        g.pose().popPose();

        if (active) {
            // Timer — large VT323-style via scaled rendering
            String timer = String.format("%02d:%02d", timerSec / 60, timerSec % 60);
            g.pose().pushPose();
            g.pose().translate(x + 4, y + 16, 0);
            g.pose().scale(1.8f, 1.8f, 1f);
            g.drawString(font, timer, 0, 0, accent, false);
            g.pose().popPose();
            // "searching..." sub-label
            small(g, "searching...", x + 4, y + 36, accent & 0xAAFFFFFF);
        } else {
            // ELO number — large
            String eloStr = String.valueOf(elo);
            g.pose().pushPose();
            g.pose().translate(x + 4, y + 16, 0);
            g.pose().scale(1.8f, 1.8f, 1f);
            g.drawString(font, eloStr, 0, 0, C_TEXT, false);
            g.pose().popPose();
        }
    }

    private void renderQueueZone(GuiGraphics g, int x, int y) {
        smallLabel(g, "Queue", x + P, y + Y_QUEUE);

        boolean q1 = queue1v1Start >= 0;
        boolean q2 = queue2v2Start >= 0;

        // Color the button backgrounds manually behind the vanilla buttons
        int btnY = y + Y_QUEUE + 10;
        int btnW = CARD_W;

        // 1v1 button background
        int bg1 = q1 ? C_RED_BG : C_SURFACE_DEEP;
        int bd1 = q1 ? C_RED_BDR : C_BLUE_BDR;
        g.fill(x + P, btnY, x + P + btnW, btnY + BTN_H, bg1);
        drawBorder(g, x + P, btnY, btnW, BTN_H, bd1);

        // 2v2 button background
        int bg2 = q2 ? C_RED_BG : C_SURFACE_DEEP;
        int bd2 = q2 ? C_RED_BDR : C_TEAL_BDR;
        int b2x = x + P + btnW + 6;
        g.fill(b2x, btnY, b2x + btnW, btnY + BTN_H, bg2);
        drawBorder(g, b2x, btnY, btnW, BTN_H, bd2);
    }

    private void renderPartyZone(GuiGraphics g, int x, int y) {
        int py = y + Y_PARTY;

        // Panel background
        int panelH = partyPanelHeight();
        g.fill(x + P, py, x + P + IW, py + panelH, C_SURFACE_DEEP);
        drawBorder(g, x + P, py, IW, panelH, C_BORDER);

        // "2v2 Party" label
        smallLabel(g, "2v2 Party", x + P + 4, py + 5);

        int cy = py + 16;

        // Member list
        if (partyMembers.isEmpty()) {
            small(g, "No party - invite someone to create one", x + P + 4, cy, C_DIM);
            cy += 10;
        } else {
            for (SyncPartyPayload.Member m : partyMembers) {
                // Star for leader
                if (m.leader()) {
                    g.drawString(font, "\u2605", x + P + 4, cy, C_GOLD, false);
                }
                // Name
                int nameColor = m.self() ? C_TEAL_NAME : C_TEXT;
                g.drawString(font, m.name(), x + P + 14, cy, nameColor, false);
                // Pending badge
                if (m.pending()) {
                    int bw = font.width("pending") + 6;
                    int bx = x + P + IW - bw - 4;
                    g.fill(bx, cy - 1, bx + bw, cy + 9, C_AMBER_BG);
                    drawBorder(g, bx, cy - 1, bw, 10, C_AMBER_BDR);
                    small(g, "pending", bx + 3, cy, C_AMBER);
                }
                cy += 11;
            }
        }

        // Divider
        cy += 2;
        g.fill(x + P + 4, cy, x + P + IW - 4, cy + 1, C_BORDER);
        cy += 5;

        // Reposition invite box to correct Y after dynamic member list
        if (inviteBox != null) {
            inviteBox.setY(cy);
            if (btnSendInvite != null) btnSendInvite.setY(cy);
        }

        // Incoming invite panel
        if (incomingInvite != null) {
            int incY = cy + 14 + 5;
            g.fill(x + P, incY, x + P + IW, incY + 18, C_GREEN_BG);
            drawBorder(g, x + P, incY, IW, 18, C_GREEN_BDR);
            small(g, "Invite from ", x + P + 4, incY + 5, C_MUTED);
            small(g, incomingInvite, x + P + 4 + font.width("Invite from "), incY + 5, C_TEAL_NAME);
            // Reposition accept/decline buttons
            if (btnAcceptInvite != null) btnAcceptInvite.setY(incY + 3);
            if (btnDeclineInvite != null) btnDeclineInvite.setY(incY + 3);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
        // suppress Minecraft's default dirt/blur background
    }

    // ── Tick ──────────────────────────────────────────────────────────────
    @Override
    public void tick() {
        super.tick();
        queue1v1Start = queuedAt1v1;
        queue2v2Start = queuedAt2v2;
        updateButtons();
    }

    // ── Input ─────────────────────────────────────────────────────────────
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (inviteBox != null && inviteBox.isFocused() && keyCode == 258) {
            applyTabCompletion(inviteBox);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── Static state setters (called from client network handlers) ─────────
    public static void applyQueueState(boolean in1v1, boolean in2v2) {
        queuedAt1v1 = in1v1 ? (queuedAt1v1 < 0 ? System.currentTimeMillis() : queuedAt1v1) : -1;
        queuedAt2v2 = in2v2 ? (queuedAt2v2 < 0 ? System.currentTimeMillis() : queuedAt2v2) : -1;
    }

    public static void applyElo(int v1, int v2) {
        elo1v1 = v1;
        elo2v2 = v2;
    }

    public static void applyPartyState(List<SyncPartyPayload.Member> members, String incomingFrom) {
        partyMembers = new ArrayList<>(members);
        incomingInvite = incomingFrom;
    }

    // ── Layout helpers ────────────────────────────────────────────────────
    private int partyMembersHeight() {
        if (partyMembers.isEmpty()) return 10; // "No party" one-liner
        return partyMembers.size() * 11;
    }

    private int partyPanelHeight() {
        // label + members + divider + invite row + optional incoming invite row
        int h = 16 + partyMembersHeight() + 2 + 5 + 14 + 6; // base
        if (incomingInvite != null) h += 18 + 5;
        return h;
    }

    private int screenHeight() {
        return Y_PARTY + partyPanelHeight() + 10;
    }

    // ── Drawing helpers ───────────────────────────────────────────────────
    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void smallLabel(GuiGraphics g, String text, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(font, text.toUpperCase(), 0, 0, C_MUTED, false);
        g.pose().popPose();
    }

    private void small(GuiGraphics g, String text, int x, int y, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.85f, 0.85f, 1f);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    private static long elapsedSecs(long startMs, long nowMs) {
        return Math.max(0, (nowMs - startMs) / 1000L);
    }

    private void applyTabCompletion(EditBox field) {
        if (minecraft == null || minecraft.getConnection() == null) return;
        String prefix = field.getValue().trim().toLowerCase();
        if (prefix.isEmpty()) return;
        String best = null;
        for (var info : minecraft.getConnection().getOnlinePlayers()) {
            String name = info.getProfile().getName();
            if (name.toLowerCase().startsWith(prefix) && (best == null || name.length() < best.length())) {
                best = name;
            }
        }
        if (best != null) field.setValue(best);
    }
}
