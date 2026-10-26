package net.ledok.duels_ld.client;

import net.ledok.vectorlib.client.canvas.ItemStackNode;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.WidgetStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Small node factories shared by the wager screens (stake rows, text blocks, buttons). */
final class WagerUi {
    static final int SLOT = 18;

    private WagerUi() {
    }

    static TextNode text(Component component, int color) {
        return TextNode.of(component).color(color).shadow(false);
    }

    /** Wrapped text, {@code width} canvas units wide. */
    static TextNode wrapped(Component component, int color, float width) {
        return TextNode.of(component).color(color).shadow(false).maxWidth((int) width);
    }

    static Flex spacer(float px) {
        Flex spacer = Flex.column();
        spacer.sizing(Sizing.fill(), Sizing.fixed(px));
        return spacer;
    }

    static Button button(UiTheme t, Component label, float width, float height, int base, int hover, int border, Runnable action) {
        Button button = new Button(width, height, label, action);
        WidgetStyle style = t.buttonStyle(base, hover, border);
        if (style != null) {
            button.style(style);
        }
        return button;
    }

    static float labelWidth(Component label) {
        return Minecraft.getInstance().font.width(label) + 14;
    }

    /** A read-only row of slot frames showing another player's stake (vanilla tooltips on hover). */
    static Flex stakeRow(UiTheme t, List<ItemStack> items, int slots) {
        Flex row = Flex.row().gap(2);
        row.sizing(Sizing.content(), Sizing.fixed(SLOT));
        int shown = Math.max(slots, items.size());
        for (int i = 0; i < shown; i++) {
            Flex frame = Flex.column().alignItems(Align.CENTER).justify(net.ledok.vectorlib.client.canvas.layout.Justify.CENTER);
            frame.sizing(Sizing.fixed(SLOT), Sizing.fixed(SLOT));
            frame.background(slotSkin(t, false, false));
            if (i < items.size()) {
                frame.item(ItemStackNode.of(items.get(i), 0, 0));
            }
            row.item(frame);
        }
        return row;
    }

    /** A slot frame look: inset panel, lighter when hovered, red when the item can't be staked. */
    static java.util.function.Function<net.ledok.vectorlib.client.canvas.Rect, net.ledok.vectorlib.client.canvas.CanvasNode> slotSkin(
            UiTheme t, boolean hovered, boolean refused) {
        int fill = refused ? 0x80B02E26 : hovered ? (t.vanillaStyle ? 0xFFA0A0A0 : t.rowBg) : (t.vanillaStyle ? 0xFF8B8B8B : t.panel2);
        int stroke = refused ? t.danger : hovered ? t.accent : (t.vanillaStyle ? 0xFF373737 : t.hairline);
        return r -> Shapes.rect(0, 0, r.width(), r.height()).fill(fill).stroke(stroke, 1);
    }

    /** A boxed paragraph (rules, the honesty warning) with a colored left edge. */
    static Flex notice(UiTheme t, Component text, int edge, int color, float width) {
        Flex box = Flex.row().gap(6).padding(Insets.of(5, 6, 5, 0));
        box.sizing(Sizing.fill(), Sizing.content());
        box.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(t.vanillaStyle ? 0x20000000 : t.panel2));
        Flex bar = Flex.column();
        bar.sizing(Sizing.fixed(2), Sizing.fill());
        bar.backgroundFill(edge);
        box.item(bar);
        box.item(wrapped(text, color, width - 14));
        return box;
    }

    /** "500 coins + 3 items" for summaries. */
    static Component stakeSummary(long coins, List<ItemStack> items) {
        int count = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                count++;
            }
        }
        String one = count == 1 ? ".one" : "";
        if (coins > 0 && count > 0) {
            return Component.translatable("duels_ld.wager.stake.both" + one, coins, count);
        }
        if (coins > 0) {
            return Component.translatable("duels_ld.wager.stake.coins", coins);
        }
        return Component.translatable("duels_ld.wager.stake.items" + one, count);
    }

    static String time(long millis) {
        long s = Math.max(0, millis / 1000);
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
