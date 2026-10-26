package net.ledok.duels_ld.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.duels_ld.DuelsLdMod;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.NineSlicePanel;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Theme;
import net.ledok.vectorlib.client.canvas.widget.WidgetStyle;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Client-side style for the mod's screens. Two built-in looks: MODERN, the flat dark
 * navy/purple theme, and VANILLA, the classic gray Minecraft panels drawn from vanilla's own
 * panel/slot textures and button sprites — no custom textures involved. The choice
 * persists per player in config/duels_ld_client.json.
 */
public final class UiTheme {

    public static final UiTheme MODERN = new UiTheme(false,
        0xFF121922, // panel
        0xFF0C1218, // panel2
        0xFF283442, // hairline
        0xFF3A4A5C, // hairlineHi
        0xFF19222D, // rowBg
        0xFFE8EEF5, // ink
        0xFF9AA8B8, // inkMid
        0xFF5F6E80, // inkDim
        0xFF86D36C, // good
        0xFFE8624A, // danger
        0xFF8F3A2C, // dangerDark
        0xFFA98BE8, // accent
        0xFF6C4FB5, // accentDark
        0xFFF5C84B  // gold
    );

    // Dark-on-light palette tuned for the classic 0xFFC6C6C6 panel texture.
    public static final UiTheme VANILLA = new UiTheme(true,
        0xFFC6C6C6, // panel
        0xFF8B8B8B, // panel2 (the vanilla slot gray)
        0xFF808080, // hairline
        0xFF555555, // hairlineHi
        0xFFA8A8A8, // rowBg (hover)
        0xFF404040, // ink (vanilla label gray)
        0xFF555555, // inkMid
        0xFF6A6A6A, // inkDim
        0xFF2D6B22, // good
        0xFFB02E26, // danger
        0xFF801E18, // dangerDark
        0xFF3D5FA0, // accent
        0xFF2A4270, // accentDark
        0xFF8A6A16  // gold
    );

    public final boolean vanillaStyle;
    public final int panel;
    public final int panel2;
    public final int hairline;
    public final int hairlineHi;
    public final int rowBg;
    public final int ink;
    public final int inkMid;
    public final int inkDim;
    public final int good;
    public final int danger;
    public final int dangerDark;
    public final int accent;
    public final int accentDark;
    public final int gold;

    private UiTheme(boolean vanillaStyle, int panel, int panel2, int hairline, int hairlineHi,
                    int rowBg, int ink, int inkMid, int inkDim, int good, int danger, int dangerDark,
                    int accent, int accentDark, int gold) {
        this.vanillaStyle = vanillaStyle;
        this.panel = panel;
        this.panel2 = panel2;
        this.hairline = hairline;
        this.hairlineHi = hairlineHi;
        this.rowBg = rowBg;
        this.ink = ink;
        this.inkMid = inkMid;
        this.inkDim = inkDim;
        this.good = good;
        this.danger = danger;
        this.dangerDark = dangerDark;
        this.accent = accent;
        this.accentDark = accentDark;
        this.gold = gold;
    }

    // ── Surfaces (Flex background factories) ───────────────────────────────

    // The classic beveled GUI panel and the inset slot, straight from vanilla's textures.
    private static final ResourceLocation PANEL_TEXTURE = ResourceLocation.withDefaultNamespace("textures/gui/demo_background.png");
    private static final ResourceLocation SLOT_SPRITE = ResourceLocation.withDefaultNamespace("textures/gui/sprites/container/slot.png");

    /** The window shell. */
    public Function<Rect, CanvasNode> shell() {
        return vanillaStyle
            ? r -> NineSlicePanel.of(PANEL_TEXTURE, 256, 256).region(0, 0, 248, 166).insets(4).bounds(0, 0, r.width(), r.height())
            : flatOutline(panel, hairlineHi);
    }

    /** Scrollable content area inside the shell (the shell texture shows through in vanilla). */
    public Function<Rect, CanvasNode> content() {
        return vanillaStyle ? null : flat(panel);
    }

    /** Header / footer / toolbar strip. */
    public Function<Rect, CanvasNode> bar() {
        return flat(vanillaStyle ? 0x18000000 : panel2);
    }

    /** Cards and sub-panels; the outline only shows in the modern style. */
    public Function<Rect, CanvasNode> inset(int outlineColor) {
        return vanillaStyle
            ? r -> NineSlicePanel.of(SLOT_SPRITE, 18, 18).insets(1).bounds(0, 0, r.width(), r.height())
            : flatOutline(panel2, outlineColor);
    }

    /** High-emphasis stat numbers (the Elo values); pure white in modern for contrast. */
    public int statValue() {
        return vanillaStyle ? ink : 0xFFFFFFFF;
    }

    /** Whether the screen dims the world behind it (the vanilla look); modern leaves it visible. */
    public boolean dimsBackground() {
        return vanillaStyle;
    }

    /** The VectorLib theme for the canvas: vanilla sprites, or flat dark skins in the palette. */
    public Theme theme() {
        WidgetStyle widgets = vanillaStyle ? WidgetStyle.DEFAULT : new WidgetStyle(
            new WidgetStyle.Skin.Flat(accentDark, hairline, 1, 0),
            new WidgetStyle.Skin.Flat(accent, hairline, 1, 0),
            new WidgetStyle.Skin.Flat(accentDark, accent, 1, 0),
            new WidgetStyle.Skin.Flat(panel, hairline, 1, 0),
            new WidgetStyle.Skin.Flat(panel2, hairline, 1, 0),
            new WidgetStyle.Skin.Flat(panel2, accent, 1, 0),
            new WidgetStyle.Skin.Flat(panel2, hairline, 1, 0),
            0xFFFFFFFF, 0xFFFFFFFF, inkDim, inkDim, accent, 0x80000000 | (accent & 0xFFFFFF),
            18, 18, 4, true);
        return new Theme(panel, panel2, hairline, ink, inkMid, inkDim,
            accent, good, gold, danger, rowBg, panel2, 0x88000000, widgets);
    }

    /**
     * A button skin in the given colors (fill, hover fill, outline) for the modern style, or null in
     * the vanilla style, where every button keeps the vanilla sprite.
     */
    public WidgetStyle buttonStyle(int base, int hover, int border) {
        if (vanillaStyle) {
            return null;
        }
        WidgetStyle w = theme().widgets();
        return new WidgetStyle(
            new WidgetStyle.Skin.Flat(base, border, 1, 0),
            new WidgetStyle.Skin.Flat(hover, border, 1, 0),
            new WidgetStyle.Skin.Flat(hover, border, 1, 0),
            new WidgetStyle.Skin.Flat(panel, hairline, 1, 0),
            w.field(), w.fieldFocused(), w.popup(),
            w.text(), w.textHover(), w.textDisabled(), w.hint(), w.accent(), w.selection(),
            w.buttonHeight(), w.fieldHeight(), w.padding(), w.textShadow());
    }

    public static Function<Rect, CanvasNode> flat(int fill) {
        return r -> Shapes.rect(0, 0, r.width(), r.height()).fill(fill);
    }

    public static Function<Rect, CanvasNode> flatOutline(int fill, int outline) {
        return r -> Shapes.rect(0, 0, r.width(), r.height()).fill(fill).stroke(outline, 1);
    }

    // ── Selection + persistence ─────────────────────────────────────────────

    private static UiTheme current;

    public static UiTheme current() {
        if (current == null) {
            current = load();
        }
        return current;
    }

    /** Flips between the two styles and persists the choice. */
    public static UiTheme toggle() {
        current = current().vanillaStyle ? MODERN : VANILLA;
        save();
        return current;
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("duels_ld_client.json");
    }

    private static UiTheme load() {
        try {
            Path path = configPath();
            if (Files.exists(path)) {
                JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (json.has("theme") && "vanilla".equalsIgnoreCase(json.get("theme").getAsString())) {
                    return VANILLA;
                }
            }
        } catch (Exception e) {
            DuelsLdMod.LOGGER.warn("Could not read duels_ld_client.json, using the modern theme", e);
        }
        return MODERN;
    }

    private static void save() {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonObject json = new JsonObject();
            json.addProperty("theme", current.vanillaStyle ? "vanilla" : "modern");
            Files.writeString(configPath(), gson.toJson(json));
        } catch (Exception e) {
            DuelsLdMod.LOGGER.warn("Could not save duels_ld_client.json", e);
        }
    }
}
