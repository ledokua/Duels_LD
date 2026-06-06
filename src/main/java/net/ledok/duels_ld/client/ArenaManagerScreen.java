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
import net.ledok.duels_ld.network.ArenaActionPayload;
import net.ledok.duels_ld.network.ArenaListPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;

/** Op-only arena manager, styled to match the Arenas_LD raid/dungeon screens (owo-lib). */
public class ArenaManagerScreen extends BaseOwoScreen<FlowLayout> {

    private static final int BG          = 0xFF070E14;
    private static final int PANEL       = 0xFF121922;
    private static final int PANEL_2     = 0xFF0C1218;
    private static final int HAIRLINE    = 0xFF283442;
    private static final int HAIRLINE_HI = 0xFF3A4A5C;
    private static final int INK         = 0xFFE8EEF5;
    private static final int INK_MID     = 0xFF9AA8B8;
    private static final int INK_DIM     = 0xFF5F6E80;
    private static final int GOOD        = 0xFF86D36C;
    private static final int DANGER      = 0xFFE8624A;
    private static final int WARN        = 0xFFF5C84B;
    private static final int ACCENT      = 0xFFA98BE8;
    private static final int ACCENT_DARK = 0xFF6C4FB5;
    private static final int DANGER_DARK = 0xFF8F3A2C;

    private static List<ArenaListPayload.Entry> entries = List.of();

    private FlowLayout contentArea;
    private LabelComponent headerCount;
    private TextBoxComponent createField;
    private String createDraft = "";

    public ArenaManagerScreen() {
        super(Component.translatable("duels_ld.screen.arena.title"));
    }

    /** Entry point from the network receiver: opens the screen or refreshes the open one. */
    public static void apply(boolean open, List<ArenaListPayload.Entry> data) {
        entries = data;
        Minecraft mc = Minecraft.getInstance();
        if (open) {
            if (mc.screen instanceof ArenaManagerScreen s) {
                s.rebuildContent();
            } else {
                mc.setScreen(new ArenaManagerScreen());
            }
        } else if (mc.screen instanceof ArenaManagerScreen s) {
            s.rebuildContent();
        }
    }

    @Override
    protected @NotNull OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, Containers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(Surface.flat(BG));
        root.alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER);

        int shellWidth = Math.max(420, Math.min(520, this.width - 24));
        int shellHeight = Math.max(280, Math.min(400, this.height - 24));
        FlowLayout shell = Containers.verticalFlow(Sizing.fixed(shellWidth), Sizing.fixed(shellHeight));
        shell.surface(Surface.flat(PANEL).and(Surface.outline(HAIRLINE_HI)));

        shell.child(buildHeader());
        shell.child(buildToolbar());

        contentArea = Containers.verticalFlow(Sizing.fill(100), Sizing.content());
        contentArea.surface(Surface.flat(PANEL));
        contentArea.padding(Insets.of(8));
        contentArea.gap(5);
        ScrollContainer<FlowLayout> scroll = Containers.verticalScroll(Sizing.fill(100), Sizing.expand(), contentArea);
        scroll.surface(Surface.flat(PANEL));
        scroll.scrollbar(ScrollContainer.Scrollbar.vanillaFlat());
        scroll.scrollbarThiccness(8);
        scroll.scrollStep(18);
        shell.child(scroll);

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
        info.child(text(Component.translatable("duels_ld.screen.arena.title"), INK));
        headerCount = Components.label(Component.empty());
        headerCount.color(Color.ofArgb(INK_DIM));
        info.child(headerCount);
        header.child(info);

        header.child(Containers.horizontalFlow(Sizing.expand(), Sizing.content()));
        ButtonComponent close = styledButton(Component.literal("×"), PANEL, ACCENT, HAIRLINE, b -> onClose());
        close.sizing(Sizing.fixed(22), Sizing.fixed(18));
        header.child(close);
        return header;
    }

    private FlowLayout buildToolbar() {
        FlowLayout bar = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        bar.surface(Surface.flat(PANEL_2));
        bar.padding(Insets.of(6, 6, 8, 8));
        bar.gap(6);
        bar.alignment(HorizontalAlignment.LEFT, VerticalAlignment.CENTER);

        FlowLayout fieldWrap = Containers.horizontalFlow(Sizing.expand(), Sizing.content());
        fieldWrap.surface(Surface.flat(PANEL).and(Surface.outline(HAIRLINE)));
        fieldWrap.alignment(HorizontalAlignment.LEFT, VerticalAlignment.CENTER);
        FlowLayout accent = Containers.verticalFlow(Sizing.fixed(2), Sizing.fixed(20));
        accent.surface(Surface.flat(ACCENT));
        fieldWrap.child(accent);
        createField = Components.textBox(Sizing.expand(), createDraft);
        createField.verticalSizing(Sizing.fixed(18));
        createField.onChanged().subscribe(v -> createDraft = v);
        fieldWrap.child(createField);
        bar.child(fieldWrap);

        ButtonComponent create = styledButton(Component.translatable("duels_ld.screen.arena.create"), ACCENT_DARK, ACCENT, ACCENT, b -> {
            String name = createField.getValue().trim();
            if (!name.isEmpty()) {
                send("create", name, "");
                createDraft = "";
                createField.text("");
            }
        });
        create.sizing(Sizing.fixed(70), Sizing.fixed(20));
        bar.child(create);
        return bar;
    }

    private void rebuildContent() {
        if (contentArea == null) {
            return;
        }
        contentArea.clearChildren();
        if (headerCount != null) {
            headerCount.text(Component.translatable("duels_ld.screen.arena.count", entries.size()));
        }
        if (entries.isEmpty()) {
            contentArea.child(text(Component.translatable("duels_ld.screen.arena.empty"), INK_DIM));
            return;
        }
        for (ArenaListPayload.Entry e : entries) {
            contentArea.child(arenaRow(e));
        }
    }

    private FlowLayout arenaRow(ArenaListPayload.Entry e) {
        FlowLayout row = Containers.verticalFlow(Sizing.fill(100), Sizing.content());
        row.surface(Surface.flat(PANEL_2).and(Surface.outline(HAIRLINE)));
        row.padding(Insets.of(6));
        row.gap(3);

        // Title line: name + active state
        FlowLayout titleLine = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        titleLine.gap(6);
        titleLine.alignment(HorizontalAlignment.LEFT, VerticalAlignment.CENTER);
        titleLine.child(text(Component.literal(e.name()), INK));
        titleLine.child(Containers.horizontalFlow(Sizing.expand(), Sizing.content()));
        if (e.active()) {
            titleLine.child(text(Component.translatable("duels_ld.screen.arena.in_use"), WARN));
        }
        row.child(titleLine);

        // Meta line: dimension + readiness badges + spawn counts
        FlowLayout meta = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        meta.gap(8);
        meta.alignment(HorizontalAlignment.LEFT, VerticalAlignment.CENTER);
        meta.child(text(Component.literal(e.dimensionId()), INK_DIM));
        meta.child(badge("1v1", e.ready1v1()));
        meta.child(badge("2v2", e.ready2v2()));
        meta.child(text(Component.translatable("duels_ld.screen.arena.spawns", e.team1(), e.team2()), INK_MID));
        if (!e.hasBounds()) {
            meta.child(text(Component.translatable("duels_ld.screen.arena.no_bounds"), WARN));
        }
        if (e.outside() > 0) {
            meta.child(text(Component.translatable("duels_ld.screen.arena.outside", e.outside()), DANGER));
        }
        row.child(meta);

        // Action buttons
        FlowLayout actions = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        actions.gap(4);
        actions.alignment(HorizontalAlignment.LEFT, VerticalAlignment.CENTER);
        actions.child(action(Component.translatable("duels_ld.screen.arena.tp"), 36, ACCENT_DARK, ACCENT, b -> send("tp", e.name(), "")));
        actions.child(action(Component.translatable("duels_ld.screen.arena.wand"), 46, ACCENT_DARK, ACCENT, b -> send("wand", e.name(), "")));
        actions.child(action(Component.translatable("duels_ld.screen.arena.pos1"), 40, ACCENT_DARK, ACCENT, b -> send("pos1", e.name(), "")));
        actions.child(action(Component.translatable("duels_ld.screen.arena.pos2"), 40, ACCENT_DARK, ACCENT, b -> send("pos2", e.name(), "")));
        actions.child(action(Component.translatable("duels_ld.screen.arena.add_t1"), 38, ACCENT_DARK, ACCENT, b -> send("addspawn", e.name(), "1")));
        actions.child(action(Component.translatable("duels_ld.screen.arena.add_t2"), 38, ACCENT_DARK, ACCENT, b -> send("addspawn", e.name(), "2")));
        actions.child(Containers.horizontalFlow(Sizing.expand(), Sizing.content()));
        actions.child(action(Component.translatable("duels_ld.screen.arena.clone"), 44, ACCENT_DARK, ACCENT, b -> send("clone", e.name(), e.name() + "_copy")));
        actions.child(action(Component.translatable("duels_ld.screen.arena.delete"), 38, DANGER_DARK, DANGER, b -> send("delete", e.name(), "")));
        row.child(actions);
        return row;
    }

    private void send(String action, String name, String arg) {
        ClientPlayNetworking.send(new ArenaActionPayload(action, name, arg));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private LabelComponent badge(String label, boolean ok) {
        return text(Component.literal((ok ? "✓ " : "✗ ") + label), ok ? GOOD : DANGER);
    }

    private ButtonComponent action(Component label, int width, int base, int hover, Consumer<ButtonComponent> onClick) {
        ButtonComponent button = styledButton(label, base, hover, hover, onClick);
        button.sizing(Sizing.fixed(width), Sizing.fixed(16));
        return button;
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
}
