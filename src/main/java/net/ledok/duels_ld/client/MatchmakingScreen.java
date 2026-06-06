package net.ledok.duels_ld.client;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.duels_ld.network.JoinQueuePayload;
import net.ledok.duels_ld.network.LeaveQueuePayload;
import net.ledok.duels_ld.network.PartyAcceptPayload;
import net.ledok.duels_ld.network.PartyInvitePayload;
import net.ledok.duels_ld.network.RequestEloPayload;
import net.ledok.duels_ld.network.SyncPartyPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Player-facing matchmaking screen, styled to match the Arenas_LD raid/dungeon screens
 * (owo-lib flat dark theme: navy shell, hairline panels, purple accent, ink text).
 */
public class MatchmakingScreen extends BaseOwoScreen<FlowLayout> {

    // ── Palette (shared with the Arenas raid/dungeon screens) ──────────────
    private static final int BG          = 0xFF070E14;
    private static final int PANEL       = 0xFF121922;
    private static final int PANEL_2     = 0xFF0C1218;
    private static final int HAIRLINE    = 0xFF283442;
    private static final int HAIRLINE_HI = 0xFF3A4A5C;
    private static final int ROW_BG      = 0xFF19222D;
    private static final int INK         = 0xFFE8EEF5;
    private static final int INK_MID     = 0xFF9AA8B8;
    private static final int INK_DIM     = 0xFF5F6E80;
    private static final int GOOD        = 0xFF86D36C;
    private static final int DANGER      = 0xFFE8624A;
    private static final int DANGER_DARK = 0xFF8F3A2C;
    private static final int ACCENT      = 0xFFA98BE8;
    private static final int ACCENT_DARK = 0xFF6C4FB5;
    private static final int GOLD        = 0xFFF5C84B;

    private static final int DROPDOWN_MAX_ROWS = 6;
    private static final int DROPDOWN_MAX_HEIGHT = 120;

    // ── Persistent client state (updated from network handlers) ────────────
    private static long queuedAt1v1 = -1;
    private static long queuedAt2v2 = -1;
    private static int elo1v1 = 0;
    private static int elo2v2 = 0;
    private static List<SyncPartyPayload.Member> partyMembers = new ArrayList<>();
    private static String incomingInvite = null;
    private static String inviteDraft = "";

    // ── Live component references ──────────────────────────────────────────
    private FlowLayout contentArea;
    private FlowLayout footerActions;
    private LabelComponent footerLabel;
    private LabelComponent value1v1;
    private LabelComponent value2v2;

    // Invite autocomplete
    private TextBoxComponent inviteField;
    private FlowLayout inviteDropdown;
    private boolean inviteDropdownOpen = false;

    public MatchmakingScreen() {
        super(Component.translatable("duels_ld.screen.matchmaking.title"));
    }

    @Override
    protected @NotNull OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, Containers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        ClientPlayNetworking.send(new RequestEloPayload());

        root.surface(Surface.flat(BG));
        root.alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER);

        int shellWidth = Math.max(340, Math.min(420, this.width - 24));
        int shellHeight = Math.max(240, Math.min(360, this.height - 24));
        FlowLayout shell = Containers.verticalFlow(Sizing.fixed(shellWidth), Sizing.fixed(shellHeight));
        shell.surface(Surface.flat(PANEL).and(Surface.outline(HAIRLINE_HI)));

        shell.child(buildHeader());

        contentArea = Containers.verticalFlow(Sizing.fill(100), Sizing.content());
        contentArea.surface(Surface.flat(PANEL));
        contentArea.padding(Insets.of(10));
        contentArea.gap(4);
        ScrollContainer<FlowLayout> scroll = Containers.verticalScroll(Sizing.fill(100), Sizing.expand(), contentArea);
        scroll.surface(Surface.flat(PANEL));
        scroll.scrollbar(ScrollContainer.Scrollbar.vanillaFlat());
        scroll.scrollbarThiccness(8);
        scroll.scrollStep(18);
        shell.child(scroll);

        shell.child(buildFooter());
        root.child(shell);

        rebuildContent();
    }

    private FlowLayout buildHeader() {
        FlowLayout header = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(46));
        header.surface(Surface.flat(PANEL_2));
        header.padding(Insets.of(8, 8, 10, 10));
        header.gap(10);
        header.alignment(HorizontalAlignment.LEFT, VerticalAlignment.CENTER);

        FlowLayout mark = Containers.verticalFlow(Sizing.fixed(20), Sizing.fixed(20));
        mark.surface(Surface.flat(ACCENT).and(Surface.outline(ACCENT_DARK)));
        header.child(mark);

        FlowLayout info = Containers.verticalFlow(Sizing.content(), Sizing.content());
        info.gap(3);
        info.child(text(Component.translatable("duels_ld.screen.matchmaking.title"), INK));

        FlowLayout live = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        live.gap(4);
        live.alignment(HorizontalAlignment.LEFT, VerticalAlignment.CENTER);
        live.child(text(Component.literal("●"), GOOD));
        live.child(text(Component.translatable("duels_ld.screen.matchmaking.online"), GOOD));
        info.child(live);
        header.child(info);

        header.child(Containers.horizontalFlow(Sizing.expand(), Sizing.content()));

        ButtonComponent close = styledButton(Component.literal("×"), PANEL, ACCENT, HAIRLINE, b -> onClose());
        close.sizing(Sizing.fixed(22), Sizing.fixed(18));
        header.child(close);
        return header;
    }

    private FlowLayout buildFooter() {
        FlowLayout footer = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(30));
        footer.surface(Surface.flat(PANEL_2));
        footer.padding(Insets.of(6));
        footer.gap(6);
        footer.alignment(HorizontalAlignment.LEFT, VerticalAlignment.CENTER);

        footerLabel = Components.label(Component.empty());
        footerLabel.color(Color.ofArgb(INK_MID));
        footerLabel.horizontalSizing(Sizing.expand());
        footer.child(footerLabel);

        footerActions = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        footerActions.gap(4);
        footer.child(footerActions);
        return footer;
    }

    // ── Content ─────────────────────────────────────────────────────────────

    private void rebuildContent() {
        if (contentArea == null) {
            return;
        }
        closeInviteDropdown();
        inviteField = null;
        inviteDropdown = null;
        contentArea.clearChildren();
        footerActions.clearChildren();

        boolean q1 = queuedAt1v1 >= 0;
        boolean q2 = queuedAt2v2 >= 0;

        // ── Rating ──────────────────────────────────────────────────────────
        contentArea.child(sectionHeader(Component.translatable("duels_ld.screen.matchmaking.my_rating")));
        FlowLayout cards = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        cards.gap(6);
        value1v1 = Components.label(Component.empty());
        value2v2 = Components.label(Component.empty());
        cards.child(buildModeCard("duels_ld.screen.matchmaking.mode_1v1", q1, value1v1, elo1v1, queuedAt1v1));
        cards.child(buildModeCard("duels_ld.screen.matchmaking.mode_2v2", q2, value2v2, elo2v2, queuedAt2v2));
        contentArea.child(cards);

        contentArea.child(spacer(8));

        // ── Queue ───────────────────────────────────────────────────────────
        contentArea.child(sectionHeader(Component.translatable("duels_ld.screen.matchmaking.queue_label")));
        FlowLayout queueRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        queueRow.gap(6);
        queueRow.child(queueButton(q1,
            q1 ? "duels_ld.screen.matchmaking.leave_queue" : "duels_ld.screen.matchmaking.queue_1v1",
            this::handle1v1Click));
        queueRow.child(queueButton(q2,
            q2 ? "duels_ld.screen.matchmaking.leave_queue" : "duels_ld.screen.matchmaking.queue_2v2",
            this::handle2v2Click));
        contentArea.child(queueRow);

        contentArea.child(spacer(8));

        // ── Party ───────────────────────────────────────────────────────────
        contentArea.child(sectionHeader(Component.translatable("duels_ld.screen.matchmaking.party_title")));
        contentArea.child(buildPartyPanel());

        // ── Footer ──────────────────────────────────────────────────────────
        if (q1 || q2) {
            ButtonComponent leaveAll = styledButton(
                Component.translatable("duels_ld.screen.matchmaking.leave_all"),
                DANGER_DARK, DANGER, DANGER, b -> handleLeaveAll());
            leaveAll.horizontalSizing(Sizing.fixed(90));
            footerActions.child(leaveAll);
        }
        updateLiveLabels();
    }

    private FlowLayout buildModeCard(String modeKey, boolean queued, LabelComponent valueLabel, int elo, long queuedAt) {
        FlowLayout card = Containers.verticalFlow(Sizing.expand(), Sizing.content());
        card.surface(Surface.flat(PANEL_2).and(Surface.outline(queued ? ACCENT_DARK : HAIRLINE)));
        card.padding(Insets.of(8));
        card.gap(3);

        if (queued) {
            FlowLayout accentBar = Containers.verticalFlow(Sizing.fill(100), Sizing.fixed(2));
            accentBar.surface(Surface.flat(ACCENT));
            card.child(accentBar);
        }

        card.child(text(Component.translatable(modeKey), queued ? ACCENT : INK_DIM));

        valueLabel.text(queued ? Component.literal(formatTimer(queuedAt)) : Component.literal(String.valueOf(elo)));
        valueLabel.color(Color.ofArgb(queued ? ACCENT : INK));
        card.child(valueLabel);

        card.child(text(
            queued ? Component.translatable("duels_ld.screen.matchmaking.searching")
                   : Component.translatable("duels_ld.screen.matchmaking.elo_caption"),
            INK_DIM));
        return card;
    }

    private ButtonComponent queueButton(boolean queued, String labelKey, Consumer<ButtonComponent> action) {
        ButtonComponent button = queued
            ? styledButton(Component.translatable(labelKey), DANGER_DARK, DANGER, DANGER, action)
            : styledButton(Component.translatable(labelKey), ACCENT_DARK, ACCENT, ACCENT, action);
        button.horizontalSizing(Sizing.expand());
        return button;
    }

    private FlowLayout buildPartyPanel() {
        FlowLayout panel = Containers.verticalFlow(Sizing.fill(100), Sizing.content());
        panel.surface(Surface.flat(PANEL_2).and(Surface.outline(HAIRLINE)));
        panel.padding(Insets.of(8));
        panel.gap(3);

        if (partyMembers.isEmpty()) {
            panel.child(text(Component.translatable("duels_ld.screen.matchmaking.no_party_hint"), INK_DIM));
        } else {
            for (SyncPartyPayload.Member m : partyMembers) {
                FlowLayout row = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
                row.gap(5);
                row.alignment(HorizontalAlignment.LEFT, VerticalAlignment.CENTER);
                row.child(text(Component.literal(m.leader() ? "★" : "·"), m.leader() ? GOLD : INK_DIM));
                row.child(text(Component.literal(m.name()), m.self() ? ACCENT : INK));
                row.child(Containers.horizontalFlow(Sizing.expand(), Sizing.content()));
                if (m.pending()) {
                    row.child(text(Component.translatable("duels_ld.screen.matchmaking.pending"), GOLD));
                }
                panel.child(row);
            }
        }

        panel.child(spacer(2));
        FlowLayout divider = Containers.verticalFlow(Sizing.fill(100), Sizing.fixed(1));
        divider.surface(Surface.flat(HAIRLINE));
        panel.child(divider);
        panel.child(spacer(2));

        // Invite row: searchable name field + Send
        FlowLayout inviteRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        inviteRow.gap(4);
        inviteRow.alignment(HorizontalAlignment.LEFT, VerticalAlignment.TOP);

        FlowLayout fieldCol = Containers.verticalFlow(Sizing.expand(), Sizing.content());
        FlowLayout fieldRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20));
        fieldRow.surface(Surface.flat(PANEL).and(Surface.outline(HAIRLINE)));
        fieldRow.alignment(HorizontalAlignment.LEFT, VerticalAlignment.CENTER);
        FlowLayout fieldAccent = Containers.verticalFlow(Sizing.fixed(2), Sizing.fill(100));
        fieldAccent.surface(Surface.flat(ACCENT));
        fieldRow.child(fieldAccent);

        inviteField = Components.textBox(Sizing.expand(), inviteDraft == null ? "" : inviteDraft);
        inviteField.verticalSizing(Sizing.fixed(16));
        inviteField.onChanged().subscribe(v -> {
            inviteDraft = v;
            if (inviteDropdownOpen) {
                refreshInviteDropdown();
            }
        });
        inviteField.mouseDown().subscribe((mx, my, btn) -> {
            openInviteDropdown();
            return false;
        });
        fieldRow.child(inviteField);
        fieldCol.child(fieldRow);

        inviteDropdown = Containers.verticalFlow(Sizing.fill(100), Sizing.content());
        inviteDropdown.surface(Surface.BLANK);
        fieldCol.child(inviteDropdown);
        inviteRow.child(fieldCol);

        ButtonComponent send = styledButton(
            Component.translatable("duels_ld.screen.matchmaking.send"), ACCENT_DARK, ACCENT, ACCENT, b -> sendInvite());
        send.sizing(Sizing.fixed(42), Sizing.fixed(20));
        inviteRow.child(send);
        panel.child(inviteRow);

        // Incoming invite
        if (incomingInvite != null) {
            panel.child(spacer(3));
            FlowLayout incoming = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
            incoming.surface(Surface.flat(PANEL).and(Surface.outline(GOOD)));
            incoming.padding(Insets.of(5));
            incoming.gap(4);
            incoming.alignment(HorizontalAlignment.LEFT, VerticalAlignment.CENTER);
            incoming.child(text(Component.translatable("duels_ld.screen.matchmaking.invite_from_prefix"), INK_MID));
            incoming.child(text(Component.literal(incomingInvite), ACCENT));
            incoming.child(Containers.horizontalFlow(Sizing.expand(), Sizing.content()));
            ButtonComponent accept = styledButton(
                Component.translatable("duels_ld.screen.matchmaking.accept"), ACCENT_DARK, ACCENT, ACCENT, b -> {
                    if (incomingInvite != null) {
                        ClientPlayNetworking.send(new PartyAcceptPayload(incomingInvite));
                    }
                });
            accept.sizing(Sizing.fixed(46), Sizing.fixed(16));
            ButtonComponent decline = styledButton(
                Component.translatable("duels_ld.screen.matchmaking.decline"), DANGER_DARK, DANGER, DANGER, b -> {
                    incomingInvite = null;
                    rebuildContent();
                });
            decline.sizing(Sizing.fixed(46), Sizing.fixed(16));
            incoming.child(accept);
            incoming.child(decline);
            panel.child(incoming);
        }
        return panel;
    }

    // ── Invite autocomplete dropdown ────────────────────────────────────────

    private void openInviteDropdown() {
        if (!inviteDropdownOpen) {
            inviteDropdownOpen = true;
            refreshInviteDropdown();
        }
    }

    private void closeInviteDropdown() {
        inviteDropdownOpen = false;
        if (inviteDropdown != null) {
            inviteDropdown.clearChildren();
            inviteDropdown.surface(Surface.BLANK);
        }
    }

    private void refreshInviteDropdown() {
        if (inviteDropdown == null) {
            return;
        }
        inviteDropdown.clearChildren();
        List<String> candidates = inviteCandidates(inviteField != null ? inviteField.getValue() : "");
        if (!inviteDropdownOpen || candidates.isEmpty()) {
            inviteDropdown.surface(Surface.BLANK);
            return;
        }
        inviteDropdown.surface(Surface.flat(PANEL_2).and(Surface.outline(HAIRLINE)));
        FlowLayout list = Containers.verticalFlow(Sizing.fill(100), Sizing.content());
        for (String name : candidates) {
            list.child(inviteRow(name));
        }
        int rowsHeight = candidates.size() * 16;
        int visible = Math.min(rowsHeight, DROPDOWN_MAX_HEIGHT);
        ScrollContainer<FlowLayout> scroll = Containers.verticalScroll(Sizing.fill(100), Sizing.fixed(visible), list);
        scroll.scrollbar(ScrollContainer.Scrollbar.vanillaFlat());
        inviteDropdown.child(scroll);
    }

    private ButtonComponent inviteRow(String name) {
        ButtonComponent row = Components.button(Component.empty(), b -> {
            if (inviteField != null) {
                inviteField.text(name);
            }
            inviteDraft = name;
            closeInviteDropdown();
        });
        row.sizing(Sizing.fill(100), Sizing.fixed(16));
        row.renderer((context, rendered, delta) -> {
            int x1 = rendered.getX();
            int y1 = rendered.getY();
            boolean hover = rendered.isHoveredOrFocused();
            context.fill(x1, y1, x1 + rendered.getWidth(), y1 + rendered.getHeight(), hover ? ROW_BG : PANEL_2);
            if (hover) {
                context.fill(x1, y1, x1 + 2, y1 + rendered.getHeight(), ACCENT);
            }
            context.drawString(this.font, name, x1 + 6, y1 + (rendered.getHeight() - this.font.lineHeight) / 2 + 1, INK, false);
        });
        return row;
    }

    private List<String> inviteCandidates(String filter) {
        if (minecraft == null || minecraft.getConnection() == null || inviteField == null || !inviteField.isFocused()) {
            return List.of();
        }
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        String self = minecraft.player != null ? minecraft.player.getGameProfile().getName() : null;
        List<String> names = new ArrayList<>();
        for (var info : minecraft.getConnection().getOnlinePlayers()) {
            String name = info.getProfile().getName();
            if (self != null && name.equalsIgnoreCase(self)) {
                continue;
            }
            if (needle.isEmpty() || name.toLowerCase(Locale.ROOT).contains(needle)) {
                names.add(name);
            }
        }
        names.sort(Comparator.naturalOrder());
        return names.size() > DROPDOWN_MAX_ROWS ? names.subList(0, DROPDOWN_MAX_ROWS) : names;
    }

    private void sendInvite() {
        String name = inviteField != null ? inviteField.getValue().trim() : "";
        if (!name.isEmpty()) {
            ClientPlayNetworking.send(new PartyInvitePayload(name));
            inviteDraft = "";
            if (inviteField != null) {
                inviteField.text("");
            }
            closeInviteDropdown();
        }
    }

    // ── Button actions ──────────────────────────────────────────────────────

    private void handle1v1Click(ButtonComponent b) {
        ClientPlayNetworking.send(queuedAt1v1 >= 0
            ? new LeaveQueuePayload(LeaveQueuePayload.MODE_1V1)
            : new JoinQueuePayload(JoinQueuePayload.MODE_1V1));
    }

    private void handle2v2Click(ButtonComponent b) {
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
            footerLabel.text(footerStatus());
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

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Tab completes the invite name to the best matching online player.
        if (keyCode == 258 && inviteField != null && inviteField.isFocused()) {
            applyTabCompletion();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void applyTabCompletion() {
        List<String> candidates = inviteCandidates(inviteField.getValue());
        if (!candidates.isEmpty()) {
            String best = candidates.stream().min(Comparator.comparingInt(String::length)).orElse(candidates.get(0));
            inviteField.text(best);
            inviteDraft = best;
        }
    }

    // ── UI helpers (mirroring the Arenas screen style) ────────────────────────

    private FlowLayout sectionHeader(Component title) {
        FlowLayout row = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.alignment(HorizontalAlignment.LEFT, VerticalAlignment.CENTER);
        row.child(text(title, INK_DIM));
        return row;
    }

    private FlowLayout spacer(int px) {
        FlowLayout spacer = Containers.verticalFlow(Sizing.fill(100), Sizing.fixed(px));
        spacer.surface(Surface.BLANK);
        return spacer;
    }

    private LabelComponent text(Component component, int color) {
        LabelComponent label = Components.label(component);
        label.color(Color.ofArgb(color));
        return label;
    }

    private ButtonComponent styledButton(Component text, int base, int hover, int border, Consumer<ButtonComponent> action) {
        ButtonComponent button = Components.button(text, action);
        button.sizing(Sizing.content(), Sizing.fixed(18));
        button.renderer((context, rendered, delta) -> {
            int fill = rendered.active() ? (rendered.isHoveredOrFocused() ? hover : base) : PANEL;
            int outline = rendered.active() ? border : HAIRLINE;
            context.fill(rendered.getX(), rendered.getY(), rendered.getX() + rendered.getWidth(), rendered.getY() + rendered.getHeight(), fill);
            context.drawRectOutline(rendered.getX(), rendered.getY(), rendered.getWidth(), rendered.getHeight(), outline);
        });
        return button;
    }

    // ── Static state setters (called from client network handlers) ────────────

    public static void applyQueueState(boolean in1v1, boolean in2v2) {
        queuedAt1v1 = in1v1 ? (queuedAt1v1 < 0 ? System.currentTimeMillis() : queuedAt1v1) : -1;
        queuedAt2v2 = in2v2 ? (queuedAt2v2 < 0 ? System.currentTimeMillis() : queuedAt2v2) : -1;
        notifyScreen();
    }

    public static void applyElo(int v1, int v2) {
        elo1v1 = v1;
        elo2v2 = v2;
        notifyScreen();
    }

    public static void applyPartyState(List<SyncPartyPayload.Member> members, String incomingFrom) {
        partyMembers = new ArrayList<>(members);
        incomingInvite = incomingFrom;
        notifyScreen();
    }

    private static void notifyScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MatchmakingScreen s) {
            mc.execute(s::rebuildContent);
        }
    }
}
