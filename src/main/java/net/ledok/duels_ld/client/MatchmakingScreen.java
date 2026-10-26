package net.ledok.duels_ld.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.duels_ld.network.JoinQueuePayload;
import net.ledok.duels_ld.network.LeaveQueuePayload;
import net.ledok.duels_ld.network.PartyStatusPayload;
import net.ledok.duels_ld.network.RequestEloPayload;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Player-facing matchmaking screen. Styling comes from {@link UiTheme}: the default MODERN
 * look matches the Arenas_LD raid/dungeon screens (flat dark navy, purple accent), while
 * VANILLA uses the classic gray Minecraft panels; a header button switches between them.
 */
public class MatchmakingScreen extends DuelsScreen {

    // ── Persistent client state (updated from network handlers) ────────────
    private static long queuedAt1v1 = -1;
    private static long queuedAt2v2 = -1;
    private static int elo1v1 = 0;
    private static int elo2v2 = 0;
    // {wins, losses, draws} per mode
    private static int[] record1v1 = new int[3];
    private static int[] record2v2 = new int[3];
    private static boolean rewardEnabled = false;
    private static long rewardEarned = 0;
    private static long rewardCap = 0;
    // Eden_LD party (formed in Eden's hub); null until the server sends it.
    private static PartyStatusPayload party = null;

    // ── Live node references (timers and footer tick without a rebuild) ─────
    private Label footerLabel;
    private TextNode value1v1;
    private TextNode value2v2;

    public MatchmakingScreen() {
        // The theme is captured here; switching themes opens a new screen (see the header button).
        super(Component.translatable("duels_ld.screen.matchmaking.title"), UiTheme.current(), 340, 420, 240, 360, null);
        ClientPlayNetworking.send(new RequestEloPayload());
    }

    @Override
    protected void build(Flex shell) {
        shell.item(buildHeader());

        boolean q1 = queuedAt1v1 >= 0;
        boolean q2 = queuedAt2v2 >= 0;
        Flex content = scrollBody(shell, Insets.of(10), 4);

        // ── Rating ──────────────────────────────────────────────────────────
        content.item(sectionHeader(Component.translatable("duels_ld.screen.matchmaking.my_rating")));
        Flex cards = Flex.row().gap(6);
        cards.sizing(Sizing.fill(), Sizing.content());
        value1v1 = text(Component.empty(), T.statValue());
        value2v2 = text(Component.empty(), T.statValue());
        cards.item(buildModeCard("duels_ld.screen.matchmaking.mode_1v1", q1, value1v1, elo1v1, record1v1, queuedAt1v1));
        cards.item(buildModeCard("duels_ld.screen.matchmaking.mode_2v2", q2, value2v2, elo2v2, record2v2, queuedAt2v2));
        content.item(cards);

        if (rewardEnabled) {
            content.item(spacer(3));
            Flex rewardRow = Flex.row().gap(4).alignItems(Align.CENTER);
            rewardRow.sizing(Sizing.fill(), Sizing.content());
            rewardRow.item(text(Component.translatable("duels_ld.screen.matchmaking.rewards_today"), T.inkDim));
            boolean capped = rewardEarned >= rewardCap;
            rewardRow.item(text(Component.literal(rewardEarned + " / " + rewardCap), capped ? T.gold : T.good));
            content.item(rewardRow);
        }

        content.item(spacer(8));

        // ── Queue ───────────────────────────────────────────────────────────
        content.item(sectionHeader(Component.translatable("duels_ld.screen.matchmaking.queue_label")));
        Flex queueRow = Flex.row().gap(6);
        queueRow.sizing(Sizing.fill(), Sizing.content());
        queueRow.item(queueButton(q1,
            q1 ? "duels_ld.screen.matchmaking.leave_queue" : "duels_ld.screen.matchmaking.queue_1v1",
            this::handle1v1Click));
        queueRow.item(queueButton(q2,
            q2 ? "duels_ld.screen.matchmaking.leave_queue" : "duels_ld.screen.matchmaking.queue_2v2",
            this::handle2v2Click));
        content.item(queueRow);

        content.item(spacer(8));

        // ── Party ───────────────────────────────────────────────────────────
        content.item(sectionHeader(Component.translatable("duels_ld.screen.matchmaking.party_title")));
        content.item(buildPartyPanel());

        shell.item(buildFooter(q1 || q2));
        updateLiveLabels();
    }

    private Flex buildHeader() {
        Flex info = Flex.column().gap(3);
        info.item(text(Component.translatable("duels_ld.screen.matchmaking.title"), T.ink));
        Flex live = Flex.row().gap(4).alignItems(Align.CENTER);
        live.item(text(Component.literal("●"), T.good));
        live.item(text(Component.translatable("duels_ld.screen.matchmaking.online"), T.good));
        info.item(live);

        Flex header = header(info);
        // Shows the style it switches TO; the new screen re-reads UiTheme.current().
        Button themeToggle = button(
            Component.translatable(T.vanillaStyle ? "duels_ld.screen.theme.modern" : "duels_ld.screen.theme.vanilla"),
            18, T.panel, T.accent, T.hairline, () -> {
                UiTheme.toggle();
                Minecraft.getInstance().setScreen(new MatchmakingScreen());
            });
        themeToggle.tooltip(Component.translatable("duels_ld.screen.theme.tooltip"));
        header.item(themeToggle);
        header.item(closeButton());
        return header;
    }

    private Flex buildFooter(boolean queued) {
        Flex footer = Flex.row().gap(6).padding(Insets.of(6)).alignItems(Align.CENTER);
        footer.sizing(Sizing.fill(), Sizing.fixed(30));
        footer.background(T.bar());

        footerLabel = Label.of(100, Component.empty()).color(T.inkMid);
        footerLabel.sizing(Sizing.expand(), Sizing.content());
        footer.item(footerLabel);

        if (queued) {
            footer.item(button(Component.translatable("duels_ld.screen.matchmaking.leave_all"), 90, 18,
                T.dangerDark, T.danger, T.danger, this::handleLeaveAll));
        }
        return footer;
    }

    private Flex buildModeCard(String modeKey, boolean queued, TextNode valueNode, int elo, int[] record, long queuedAt) {
        Flex card = Flex.column().gap(3).padding(Insets.of(8));
        card.sizing(Sizing.expand(), Sizing.content());
        card.background(T.inset(queued ? T.accentDark : T.hairline));

        if (queued) {
            Flex accentBar = Flex.column();
            accentBar.sizing(Sizing.fill(), Sizing.fixed(2));
            accentBar.backgroundFill(T.accent);
            card.item(accentBar);
        }

        card.item(text(Component.translatable(modeKey), queued ? T.accent : T.inkDim));

        valueNode.text(queued ? Component.literal(formatTimer(queuedAt)) : Component.literal(String.valueOf(elo)));
        valueNode.color(queued ? T.accent : T.statValue());
        card.item(valueNode);

        card.item(text(
            queued ? Component.translatable("duels_ld.screen.matchmaking.searching")
                   : Component.translatable("duels_ld.screen.matchmaking.elo_caption"),
            T.inkDim));
        card.item(text(Component.translatable("duels_ld.screen.matchmaking.record", record[0], record[1], record[2]), T.inkMid));
        return card;
    }

    private Button queueButton(boolean queued, String labelKey, Runnable action) {
        Button button = queued
            ? button(Component.translatable(labelKey), 100, 18, T.dangerDark, T.danger, T.danger, action)
            : button(Component.translatable(labelKey), 100, 18, T.accentDark, T.accent, T.accent, action);
        button.sizing(Sizing.expand(), Sizing.fixed(18));
        return button;
    }

    /** Read-only: parties are formed and managed in Eden_LD's hub. */
    private Flex buildPartyPanel() {
        Flex panel = Flex.column().gap(3).padding(Insets.of(8));
        panel.sizing(Sizing.fill(), Sizing.content());
        panel.background(T.inset(T.hairline));

        if (party != null && !party.edenPresent()) {
            panel.item(text(Component.translatable("duels_ld.screen.matchmaking.party_no_eden"), T.inkDim));
            return panel;
        }
        if (party == null || !party.inParty()) {
            panel.item(text(Component.translatable("duels_ld.screen.matchmaking.no_party_hint"), T.inkDim));
            return panel;
        }

        boolean ready = party.size() == 2;
        Flex row = Flex.row().gap(5).alignItems(Align.CENTER);
        row.sizing(Sizing.fill(), Sizing.content());
        row.item(text(Component.literal(party.leader() ? "★" : "·"), party.leader() ? T.gold : T.inkDim));
        row.item(text(Component.translatable("duels_ld.screen.matchmaking.party_line", party.name()), T.ink));
        row.spacer();
        row.item(text(Component.literal(party.size() + "/2"), ready ? T.good : T.danger));
        panel.item(row);

        String hintKey = !ready ? "duels_ld.screen.matchmaking.party_wrong_size"
            : party.leader() ? "duels_ld.screen.matchmaking.party_ready"
            : "duels_ld.screen.matchmaking.party_leader_queues";
        panel.item(text(Component.translatable(hintKey), T.inkDim));
        return panel;
    }

    // ── Button actions ──────────────────────────────────────────────────────

    private void handle1v1Click() {
        ClientPlayNetworking.send(queuedAt1v1 >= 0
            ? new LeaveQueuePayload(LeaveQueuePayload.MODE_1V1)
            : new JoinQueuePayload(JoinQueuePayload.MODE_1V1));
    }

    private void handle2v2Click() {
        ClientPlayNetworking.send(queuedAt2v2 >= 0
            ? new LeaveQueuePayload(LeaveQueuePayload.MODE_2V2)
            : new JoinQueuePayload(JoinQueuePayload.MODE_2V2));
    }

    private void handleLeaveAll() {
        ClientPlayNetworking.send(new LeaveQueuePayload(LeaveQueuePayload.MODE_ALL));
    }

    // ── Live updates (timers + footer), without rebuilding the layout ─────────

    @Override
    public void tick() {
        super.tick();
        updateLiveLabels();
    }

    private void updateLiveLabels() {
        if (value1v1 != null && queuedAt1v1 >= 0) {
            value1v1.text(Component.literal(formatTimer(queuedAt1v1)));
        }
        if (value2v2 != null && queuedAt2v2 >= 0) {
            value2v2.text(Component.literal(formatTimer(queuedAt2v2)));
        }
        if (footerLabel != null) {
            Component status = footerStatus();
            if (!status.equals(footerLabel.text())) {
                footerLabel.text(status);
            }
        }
    }

    private Component footerStatus() {
        boolean q1 = queuedAt1v1 >= 0;
        boolean q2 = queuedAt2v2 >= 0;
        if (!q1 && !q2) {
            return Component.translatable("duels_ld.screen.matchmaking.status_idle");
        }
        StringBuilder sb = new StringBuilder();
        if (q1) {
            sb.append("1v1 ").append(formatTimer(queuedAt1v1));
        }
        if (q2) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append("2v2 ").append(formatTimer(queuedAt2v2));
        }
        return Component.translatable("duels_ld.screen.matchmaking.status_searching", sb.toString());
    }

    private static String formatTimer(long startMs) {
        long s = startMs < 0 ? 0 : Math.max(0, (System.currentTimeMillis() - startMs) / 1000L);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    // ── Static state setters (called from client network handlers) ────────────

    public static void applyQueueState(boolean in1v1, boolean in2v2) {
        queuedAt1v1 = in1v1 ? (queuedAt1v1 < 0 ? System.currentTimeMillis() : queuedAt1v1) : -1;
        queuedAt2v2 = in2v2 ? (queuedAt2v2 < 0 ? System.currentTimeMillis() : queuedAt2v2) : -1;
        notifyScreen();
    }

    public static void applyElo(int v1, int v2, int[] r1, int[] r2) {
        elo1v1 = v1;
        elo2v2 = v2;
        record1v1 = r1;
        record2v2 = r2;
        notifyScreen();
    }

    public static void applyRewardStatus(boolean enabled, long earned, long cap) {
        rewardEnabled = enabled;
        rewardEarned = earned;
        rewardCap = cap;
        notifyScreen();
    }

    public static void applyPartyStatus(PartyStatusPayload status) {
        party = status;
        notifyScreen();
    }

    /** Drops all server-session state on disconnect; the next server re-syncs what it needs. */
    public static void resetSessionState() {
        queuedAt1v1 = -1;
        queuedAt2v2 = -1;
        elo1v1 = 0;
        elo2v2 = 0;
        record1v1 = new int[3];
        record2v2 = new int[3];
        rewardEnabled = false;
        rewardEarned = 0;
        rewardCap = 0;
        party = null;
    }

    private static void notifyScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MatchmakingScreen s) {
            mc.execute(s::rebuild);
        }
    }
}
