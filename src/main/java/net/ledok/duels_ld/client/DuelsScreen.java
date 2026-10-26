package net.ledok.duels_ld.client;

import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.VectorCanvas;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.WidgetStyle;
import net.ledok.vectorlib.client.presentation.CanvasScreen;
import net.ledok.vectorlib.client.presentation.Placement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Base for the mod's VectorLib screens: a centered shell sized from the window (clamped between a
 * minimum and maximum, scaled down when the window is smaller than the minimum), rebuilt on every
 * window resize, plus the small node factories the screens share.
 */
public abstract class DuelsScreen extends CanvasScreen {

    protected final UiTheme T;
    private final int minW, maxW, minH, maxH;
    @Nullable
    private final Integer backdrop;
    @Nullable
    private ScrollPanel scroll;

    /** @param backdrop opaque color behind the shell, or null for the theme's default (dim or none) */
    protected DuelsScreen(Component title, UiTheme theme, int minW, int maxW, int minH, int maxH, @Nullable Integer backdrop) {
        super(title, VectorCanvas.create(minW, minH), Placement.Screen.center());
        this.T = theme;
        this.minW = minW;
        this.maxW = maxW;
        this.minH = minH;
        this.maxH = maxH;
        this.backdrop = backdrop;
        canvas.theme(theme.theme());
        dimBackground(theme.dimsBackground());
    }

    /** Builds the shell's content (header, body, footer) into the column {@code shell}. */
    protected abstract void build(Flex shell);

    @Override
    protected void init() {
        super.init();
        int w = Math.max(minW, Math.min(maxW, width - 24));
        int h = Math.max(minH, Math.min(maxH, height - 24));
        canvas.size(w, h);
        float scale = Math.min(1f, Math.min(width / (float) w, height / (float) h));
        placement(Placement.Screen.center().scale(scale));
        rebuild();
    }

    /** Throws the shell away and builds it again (theme change, window resize). */
    protected void rebuild() {
        float scrollY = scroll != null ? scroll.scrollY() : 0;
        scroll = null;
        canvas.clear();
        float w = canvas.width();
        float h = canvas.height();
        Flex shell = canvas.add(Flex.column());
        shell.sizing(Sizing.fixed(w), Sizing.fixed(h));
        shell.background(T.shell());
        build(shell);
        shell.layoutIn(w, h);
        if (scroll != null) {
            scroll.scrollTo(scrollY);
        }
    }

    @Override
    protected void drawBackground(GuiGraphics graphics) {
        if (backdrop != null) {
            graphics.fill(0, 0, width, height, backdrop);
        } else {
            super.drawBackground(graphics);
        }
    }

    // ── Node factories ──────────────────────────────────────────────────────

    protected TextNode text(Component component, int color) {
        return TextNode.of(component).color(color).shadow(false);
    }

    protected Flex spacer(float px) {
        Flex spacer = Flex.column();
        spacer.sizing(Sizing.fill(), Sizing.fixed(px));
        return spacer;
    }

    protected Flex sectionHeader(Component title) {
        Flex row = Flex.row().alignItems(Align.CENTER);
        row.sizing(Sizing.fill(), Sizing.content());
        row.item(text(title, T.inkDim));
        return row;
    }

    /** A content-width button in the given colors (modern) or the vanilla sprite. */
    protected Button button(Component label, float height, int base, int hover, int border, Runnable action) {
        return button(label, Minecraft.getInstance().font.width(label) + 12, height, base, hover, border, action);
    }

    protected Button button(Component label, float width, float height, int base, int hover, int border, Runnable action) {
        Button button = new Button(width, height, label, action);
        WidgetStyle style = T.buttonStyle(base, hover, border);
        if (style != null) {
            button.style(style);
        }
        return button;
    }

    /** The header strip: accent mark, title block, then {@code trailing} nodes on the right. */
    protected Flex header(Flex titleBlock) {
        Flex header = Flex.row().gap(10).padding(Insets.of(8, 10, 8, 10)).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.fixed(46));
        header.background(T.bar());

        Flex mark = Flex.column();
        mark.sizing(Sizing.fixed(20), Sizing.fixed(20));
        mark.backgroundFill(T.accent, T.accentDark, 1);
        header.item(mark);
        header.item(titleBlock);
        header.spacer();
        return header;
    }

    protected Button closeButton() {
        return button(Component.literal("×"), 22, 18, T.panel, T.accent, T.hairline, this::onClose);
    }

    /** A scrolling body that takes the shell's remaining height; add rows to the returned column. */
    protected Flex scrollBody(Flex shell, Insets padding, float gap) {
        Flex content = Flex.column().gap(gap).padding(padding);
        content.sizing(Sizing.fill(), Sizing.content());
        // Kept so a rebuild returns to the same scroll position.
        scroll = new ScrollPanel(100, 100, content);
        scroll.sizing(Sizing.fill(), Sizing.expand());
        scroll.barWidth(8);
        scroll.wheelStep(18);
        if (T.content() != null) {
            Flex frame = Flex.column();
            frame.sizing(Sizing.fill(), Sizing.expand());
            frame.background(T.content());
            frame.item(scroll);
            scroll.sizing(Sizing.fill(), Sizing.fill());
            shell.item(frame);
        } else {
            shell.item(scroll);
        }
        return content;
    }
}
