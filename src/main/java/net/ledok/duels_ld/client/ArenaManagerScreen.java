package net.ledok.duels_ld.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.duels_ld.network.ArenaActionPayload;
import net.ledok.duels_ld.network.ArenaListPayload;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Op-only arena manager, styled to match the Arenas_LD raid/dungeon screens (flat dark theme). */
public class ArenaManagerScreen extends DuelsScreen {

    private static final int BG = 0xFF070E14;

    private static List<ArenaListPayload.Entry> entries = List.of();
    private static boolean wandMode = false;

    private String createDraft = "";
    private String renaming = null;
    private String renameDraft = "";

    public ArenaManagerScreen() {
        super(Component.translatable("duels_ld.screen.arena.title"), UiTheme.MODERN, 420, 520, 280, 400, BG);
    }

    /** Entry point from the network receiver: opens the screen (manager or wand) or refreshes it. */
    public static void apply(int target, List<ArenaListPayload.Entry> data) {
        entries = data;
        Minecraft mc = Minecraft.getInstance();
        if (target == ArenaListPayload.OPEN_GUI || target == ArenaListPayload.OPEN_WAND) {
            wandMode = target == ArenaListPayload.OPEN_WAND;
            if (mc.screen instanceof ArenaManagerScreen s) {
                s.rebuild();
            } else {
                mc.setScreen(new ArenaManagerScreen());
            }
        } else if (mc.screen instanceof ArenaManagerScreen s) {
            s.rebuild();
        }
    }

    /** Drops the server-sent arena list on disconnect; wandMode is a client UI toggle and survives. */
    public static void resetSessionState() {
        entries = List.of();
    }

    @Override
    protected void build(Flex shell) {
        Flex info = Flex.column().gap(3);
        info.item(text(Component.translatable("duels_ld.screen.arena.title"), T.ink));
        info.item(text(Component.translatable("duels_ld.screen.arena.count", entries.size()), T.inkDim));
        Flex header = header(info);
        header.item(closeButton());
        shell.item(header);
        shell.item(buildToolbar());

        Flex content = scrollBody(shell, Insets.of(8), 5);
        if (entries.isEmpty()) {
            content.item(text(Component.translatable("duels_ld.screen.arena.empty"), T.inkDim));
            return;
        }
        for (ArenaListPayload.Entry e : entries) {
            content.item(arenaRow(e));
        }
    }

    private Flex buildToolbar() {
        Flex bar = Flex.row().gap(6).padding(Insets.of(6, 8, 6, 8)).alignItems(Align.CENTER);
        bar.sizing(Sizing.fill(), Sizing.content());
        bar.background(T.bar());

        TextField createField = new TextField(100, createDraft, v -> createDraft = v);
        createField.sizing(Sizing.expand(), Sizing.fixed(20));
        Runnable create = () -> {
            String name = createDraft.trim();
            if (!name.isEmpty()) {
                send("create", name, "");
                createDraft = "";
                createField.text("");
            }
        };
        createField.onSubmit(create);
        bar.item(createField);
        bar.item(button(Component.translatable("duels_ld.screen.arena.create"), 70, 20, T.accentDark, T.accent, T.accent, create));
        return bar;
    }

    private Flex arenaRow(ArenaListPayload.Entry e) {
        Flex row = Flex.column().gap(3).padding(Insets.of(6));
        row.sizing(Sizing.fill(), Sizing.content());
        row.backgroundFill(T.panel2, T.hairline, 1);

        // Title line: name + active state
        Flex titleLine = Flex.row().gap(6).alignItems(Align.CENTER);
        titleLine.sizing(Sizing.fill(), Sizing.content());
        titleLine.item(text(Component.literal(e.name()), T.ink));
        titleLine.spacer();
        if (e.active()) {
            titleLine.item(text(Component.translatable("duels_ld.screen.arena.in_use"), T.gold));
        }
        row.item(titleLine);

        // Meta line: dimension + readiness badges + spawn counts
        Flex meta = Flex.row().gap(8).alignItems(Align.CENTER);
        meta.sizing(Sizing.fill(), Sizing.content());
        meta.item(text(Component.literal(e.dimensionId()), T.inkDim));
        meta.item(badge("1v1", e.ready1v1()));
        meta.item(badge("2v2", e.ready2v2()));
        meta.item(text(Component.translatable("duels_ld.screen.arena.spawns", e.team1(), e.team2()), T.inkMid));
        if (!e.hasBounds()) {
            meta.item(text(Component.translatable("duels_ld.screen.arena.no_bounds"), T.gold));
        }
        if (e.outside() > 0) {
            meta.item(text(Component.translatable("duels_ld.screen.arena.outside", e.outside()), T.danger));
        }
        row.item(meta);

        if (e.name().equals(renaming)) {
            row.item(renameRow(e));
            return row;
        }

        // Action buttons
        Flex actions = Flex.row().gap(4).alignItems(Align.CENTER);
        actions.sizing(Sizing.fill(), Sizing.content());
        if (wandMode) {
            actions.item(action(Component.translatable("duels_ld.screen.arena.select"), 50, T.accent, T.accent, () -> {
                send("bind", e.name(), "");
                onClose();
            }));
        }
        actions.item(action(Component.translatable("duels_ld.screen.arena.tp"), 36, T.accentDark, T.accent, () -> send("tp", e.name(), "")));
        actions.item(action(Component.translatable("duels_ld.screen.arena.wand"), 46, T.accentDark, T.accent, () -> {
            send("wand", e.name(), "");
            onClose();
        }));
        actions.item(action(Component.translatable("duels_ld.screen.arena.rename"), 50, T.accentDark, T.accent, () -> {
            renaming = e.name();
            renameDraft = e.name();
            rebuild();
        }));
        actions.spacer();
        actions.item(action(Component.translatable("duels_ld.screen.arena.delete"), 38, T.dangerDark, T.danger, () -> send("delete", e.name(), "")));
        row.item(actions);
        return row;
    }

    private Flex renameRow(ArenaListPayload.Entry e) {
        Flex line = Flex.row().gap(4).alignItems(Align.CENTER);
        line.sizing(Sizing.fill(), Sizing.content());

        TextField field = new TextField(100, renameDraft, v -> renameDraft = v);
        field.sizing(Sizing.expand(), Sizing.fixed(18));
        Runnable confirm = () -> {
            String newName = renameDraft.trim();
            if (!newName.isEmpty() && !newName.equals(e.name())) {
                send("rename", e.name(), newName);
            }
            renaming = null;
            rebuild();
        };
        field.onSubmit(confirm);
        line.item(field);

        line.item(action(Component.literal("✓"), 22, T.accentDark, T.accent, confirm));
        line.item(action(Component.literal("✗"), 22, T.dangerDark, T.danger, () -> {
            renaming = null;
            rebuild();
        }));
        return line;
    }

    private void send(String action, String name, String arg) {
        ClientPlayNetworking.send(new ArenaActionPayload(action, name, arg));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TextNode badge(String label, boolean ok) {
        return text(Component.literal((ok ? "✓ " : "✗ ") + label), ok ? T.good : T.danger);
    }

    private Button action(Component label, float width, int base, int hover, Runnable onClick) {
        return button(label, width, 16, base, hover, hover, onClick);
    }
}
