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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Client-side look of the player screens (staff screens stay MODERN). Four styles:
 * <ul>
 *   <li>{@link Style#HUB} — whatever theme the player picked in Eden_LD's hub (parchment, dark,
 *   overgrown, stone tablet, resource-pack themes…), read from Eden's {@code EdenHubThemeApi}
 *   each time a screen opens. Offered only when that API is there; the default then.</li>
 *   <li>{@link Style#PARCHMENT} — the family parchment look, from Duels' own copy of the
 *   textures (works without Eden; the default without it).</li>
 *   <li>{@link Style#MODERN} — flat dark navy/purple.</li>
 *   <li>{@link Style#VANILLA} — classic gray Minecraft panels and button sprites.</li>
 * </ul>
 * The choice persists per player in {@code config/duels_ld_client.json}.
 */
public final class UiTheme {

    public enum Style {
        HUB, PARCHMENT, MODERN, VANILLA;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public Component label() {
            return Component.translatable("duels_ld.screen.theme." + key());
        }
    }

    public static final UiTheme MODERN = new UiTheme(Style.MODERN, null, null,
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
    public static final UiTheme VANILLA = new UiTheme(Style.VANILLA, null, null,
        0xFFC6C6C6, 0xFF8B8B8B, 0xFF808080, 0xFF555555, 0xFFA8A8A8,
        0xFF404040, 0xFF555555, 0xFF6A6A6A,
        0xFF2D6B22, 0xFFB02E26, 0xFF801E18, 0xFF3D5FA0, 0xFF2A4270, 0xFF8A6A16);

    // ── Parchment (Duels' own copy of the family look) ───────────────────────

    private static final String PARCHMENT_FOLDER = "textures/gui/parchment/";
    private static final int PARCHMENT_TINT = 0xFFE4E4E4;

    private static ResourceLocation parchmentTexture(String name) {
        return ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, PARCHMENT_FOLDER + name + ".png");
    }

    private static Function<Rect, CanvasNode> parchmentPanel() {
        ResourceLocation texture = parchmentTexture("panel");
        return r -> NineSlicePanel.of(texture, 48, 48).insets(8).centerMode(NineSlicePanel.Mode.TILE)
            .tint(PARCHMENT_TINT).bounds(0, 0, r.width(), r.height());
    }

    private static WidgetStyle parchmentWidgets(int ink, int hairline, int accent, int inkDim) {
        WidgetStyle.Skin normal = new WidgetStyle.Skin.NineSlice(parchmentTexture("button_normal"), 32, 16, 4);
        WidgetStyle.Skin hover = new WidgetStyle.Skin.NineSlice(parchmentTexture("button_hover"), 32, 16, 4);
        WidgetStyle.Skin disabled = new WidgetStyle.Skin.NineSlice(parchmentTexture("button_disabled"), 32, 16, 4);
        return new WidgetStyle(normal, hover, hover, disabled,
            new WidgetStyle.Skin.Flat(0x2E000000 | (ink & 0xFFFFFF), hairline, 1, 2),
            new WidgetStyle.Skin.Flat(0x2E000000 | (ink & 0xFFFFFF), accent, 1, 2),
            new WidgetStyle.Skin.NineSlice(parchmentTexture("panel"), 48, 48, 8),
            0xFF3B2612, 0xFF000000, 0xFF7A7060, inkDim, accent, 0x55000000 | (accent & 0xFFFFFF),
            18, 16, 4, false);
    }

    private static UiTheme parchment() {
        int panel = 0xFFE5D8B9, panel2 = 0xFFDACAA5, hairline = 0xFF5A4632, hairlineHi = 0xFF3B2612;
        int rowBg = 0xFFD8C69E, ink = 0xFF2B1B0E, inkMid = 0xFF4A2E16, inkDim = 0xFF8A7A60;
        int good = 0xFF3E6B2F, danger = 0xFF8B2F23, accent = 0xFF7A4A1E, gold = 0xFF9A6A10;
        WidgetStyle widgets = parchmentWidgets(ink, hairline, accent, inkDim);
        Theme theme = new Theme(panel, panel2, hairline, ink, inkMid, inkDim, accent, good, gold, danger,
            rowBg, 0xFFD2BF94, 0x88000000, widgets);
        return new UiTheme(Style.PARCHMENT, theme, parchmentPanel(), panel, panel2, hairline, hairlineHi, rowBg,
            ink, inkMid, inkDim, good, danger, darken(danger), accent, darken(accent), gold);
    }

    /** The Eden hub's current theme as a Duels theme, or null when Eden (or its theme API) isn't there. */
    @Nullable
    private static UiTheme hub() {
        EdenTheme.View view = EdenTheme.read();
        if (view == null) {
            return null;
        }
        Theme t = view.theme();
        int hairlineHi = mix(t.hairline(), t.ink(), 0.4f);
        UiTheme ui = new UiTheme(Style.HUB, t, view.panel(), t.surface(), t.surfaceAlt(), t.hairline(), hairlineHi,
            t.rowA() != 0 ? t.rowA() : t.surfaceAlt(), t.ink(), t.inkMid(), t.inkDim(), t.success(), t.danger(),
            darken(t.danger()), t.accent(), darken(t.accent()), t.warning());
        ui.hubId = view.id();
        return ui;
    }

    // ── Instance ─────────────────────────────────────────────────────────────

    public final Style style;
    /** Classic vanilla sprites (VANILLA only). */
    public final boolean vanillaStyle;
    /** Textured theme panels and buttons (PARCHMENT, HUB): custom button colors don't apply. */
    public final boolean textured;
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

    @Nullable private final Theme texturedTheme;
    @Nullable private final Function<Rect, CanvasNode> texturedPanel;
    @Nullable private ResourceLocation hubId;

    private UiTheme(Style style, @Nullable Theme texturedTheme, @Nullable Function<Rect, CanvasNode> texturedPanel,
                    int panel, int panel2, int hairline, int hairlineHi,
                    int rowBg, int ink, int inkMid, int inkDim, int good, int danger, int dangerDark,
                    int accent, int accentDark, int gold) {
        this.style = style;
        this.vanillaStyle = style == Style.VANILLA;
        this.textured = texturedTheme != null;
        this.texturedTheme = texturedTheme;
        this.texturedPanel = texturedPanel;
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
        if (texturedPanel != null) {
            return texturedPanel;
        }
        return vanillaStyle
            ? r -> NineSlicePanel.of(PANEL_TEXTURE, 256, 256).region(0, 0, 248, 166).insets(4).bounds(0, 0, r.width(), r.height())
            : flatOutline(panel, hairlineHi);
    }

    /** Room to leave inside the shell for its border art (textured panels draw an 8 px frame). */
    public float shellPadding() {
        return textured ? 6 : 0;
    }

    /** Scrollable content area inside the shell (the shell texture shows through when null). */
    @Nullable
    public Function<Rect, CanvasNode> content() {
        return vanillaStyle || textured ? null : flat(panel);
    }

    /** Header / footer / toolbar strip. */
    public Function<Rect, CanvasNode> bar() {
        if (textured) {
            return flat(0x1A000000 | (ink & 0xFFFFFF));
        }
        return flat(vanillaStyle ? 0x18000000 : panel2);
    }

    /** Cards and sub-panels. */
    public Function<Rect, CanvasNode> inset(int outlineColor) {
        if (vanillaStyle) {
            return r -> NineSlicePanel.of(SLOT_SPRITE, 18, 18).insets(1).bounds(0, 0, r.width(), r.height());
        }
        return flatOutline(panel2, outlineColor);
    }

    /** High-emphasis stat numbers (the Elo values); pure white on the dark modern theme. */
    public int statValue() {
        return style == Style.MODERN ? 0xFFFFFFFF : ink;
    }

    /** Whether the screen dims the world behind it (everything but modern, which leaves it visible). */
    public boolean dimsBackground() {
        return style != Style.MODERN;
    }

    /** The VectorLib theme for the canvas. */
    public Theme theme() {
        if (texturedTheme != null) {
            return texturedTheme;
        }
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
     * A button skin in the given colors (fill, hover fill, outline) for the modern style, or null
     * where every button keeps the theme's own look (vanilla sprites, textured themes).
     */
    @Nullable
    public WidgetStyle buttonStyle(int base, int hover, int border) {
        if (vanillaStyle || textured) {
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

    private static int darken(int argb) {
        return mix(argb, 0xFF000000, 0.3f);
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8)
            | Math.round(ab + (bb - ab) * t);
    }

    // ── Selection + persistence ─────────────────────────────────────────────

    @Nullable
    private static Style chosen;
    @Nullable
    private static UiTheme cachedParchment;

    /** The player's style as a theme; HUB follows Eden's current hub theme (read now). */
    public static UiTheme current() {
        Style style = style();
        return switch (style) {
            case HUB -> {
                UiTheme hub = hub();
                yield hub != null ? hub : parchmentTheme();
            }
            case PARCHMENT -> parchmentTheme();
            case MODERN -> MODERN;
            case VANILLA -> VANILLA;
        };
    }

    /** Whether the hub theme this screen was built with is no longer Eden's current one. */
    public boolean stale() {
        return style == Style.HUB && hubId != null && !hubId.equals(EdenTheme.id());
    }

    public static Style style() {
        if (chosen == null) {
            chosen = load();
        }
        if (chosen == Style.HUB && !EdenTheme.available()) {
            return Style.PARCHMENT;
        }
        return chosen;
    }

    /** The styles the player can pick here, in toggle order (HUB only with Eden's theme API). */
    public static List<Style> styles() {
        List<Style> out = new ArrayList<>();
        if (EdenTheme.available()) {
            out.add(Style.HUB);
        }
        out.add(Style.PARCHMENT);
        out.add(Style.MODERN);
        out.add(Style.VANILLA);
        return out;
    }

    /** The style the toggle button switches to next. */
    public static Style next() {
        List<Style> styles = styles();
        int i = styles.indexOf(style());
        return styles.get((i + 1) % styles.size());
    }

    /** Switches to the next style and persists the choice. */
    public static void toggle() {
        chosen = next();
        save();
    }

    private static UiTheme parchmentTheme() {
        if (cachedParchment == null) {
            cachedParchment = parchment();
        }
        return cachedParchment;
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("duels_ld_client.json");
    }

    private static Style load() {
        try {
            Path path = configPath();
            if (Files.exists(path)) {
                JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (json.has("theme")) {
                    String value = json.get("theme").getAsString().toUpperCase(Locale.ROOT);
                    for (Style style : Style.values()) {
                        if (style.name().equals(value)) {
                            return style;
                        }
                    }
                }
            }
        } catch (Exception e) {
            DuelsLdMod.LOGGER.warn("Could not read duels_ld_client.json, using the default theme", e);
        }
        return EdenTheme.available() ? Style.HUB : Style.PARCHMENT;
    }

    private static void save() {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonObject json = new JsonObject();
            json.addProperty("theme", chosen == null ? "hub" : chosen.key());
            Files.writeString(configPath(), gson.toJson(json));
        } catch (Exception e) {
            DuelsLdMod.LOGGER.warn("Could not save duels_ld_client.json", e);
        }
    }

    /**
     * Eden_LD's client theme API ({@code net.ledok.eden_ld.api.client.EdenHubThemeApi}, Eden 1.3.13+),
     * found by reflection so Duels runs with any Eden version or none.
     */
    static final class EdenTheme {
        record View(ResourceLocation id, Theme theme, Function<Rect, CanvasNode> panel) {
        }

        private static boolean looked;
        @Nullable private static java.lang.reflect.Method themeMethod;
        @Nullable private static java.lang.reflect.Method panelMethod;
        @Nullable private static java.lang.reflect.Method idMethod;

        private static void look() {
            if (looked) {
                return;
            }
            looked = true;
            if (!FabricLoader.getInstance().isModLoaded("eden_ld")) {
                return;
            }
            try {
                Class<?> api = Class.forName("net.ledok.eden_ld.api.client.EdenHubThemeApi");
                themeMethod = api.getMethod("theme");
                panelMethod = api.getMethod("panel");
                idMethod = api.getMethod("id");
            } catch (ReflectiveOperationException | LinkageError e) {
                themeMethod = panelMethod = idMethod = null;
            }
        }

        static boolean available() {
            look();
            return themeMethod != null;
        }

        @Nullable
        static ResourceLocation id() {
            if (!available()) {
                return null;
            }
            try {
                return (ResourceLocation) idMethod.invoke(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        @Nullable
        static View read() {
            if (!available()) {
                return null;
            }
            try {
                Theme theme = (Theme) themeMethod.invoke(null);
                Function<Rect, CanvasNode> panel = (Function<Rect, CanvasNode>) panelMethod.invoke(null);
                ResourceLocation id = (ResourceLocation) idMethod.invoke(null);
                return theme == null || panel == null ? null : new View(id, theme, panel);
            } catch (ReflectiveOperationException | RuntimeException e) {
                DuelsLdMod.LOGGER.warn("Couldn't read Eden's hub theme; using parchment.", e);
                return null;
            }
        }
    }
}
