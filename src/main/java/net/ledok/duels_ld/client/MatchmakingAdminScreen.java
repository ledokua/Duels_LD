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
import net.ledok.duels_ld.network.SyncMatchmakingSettingsPayload;
import net.ledok.duels_ld.network.UpdateMatchmakingSettingsPayload;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Admin settings form, styled to match the Arenas_LD raid/dungeon screens
 * (owo-lib flat dark theme). Edits duration / win-HP / scoring weights.
 */
public class MatchmakingAdminScreen extends BaseOwoScreen<FlowLayout> {

    private static final int BG          = 0xFF070E14;
    private static final int PANEL       = 0xFF121922;
    private static final int PANEL_2     = 0xFF0C1218;
    private static final int HAIRLINE    = 0xFF283442;
    private static final int HAIRLINE_HI = 0xFF3A4A5C;
    private static final int INK         = 0xFFE8EEF5;
    private static final int INK_MID     = 0xFF9AA8B8;
    private static final int INK_DIM     = 0xFF5F6E80;
    private static final int DANGER      = 0xFFE8624A;
    private static final int ACCENT      = 0xFFA98BE8;
    private static final int ACCENT_DARK = 0xFF6C4FB5;

    private TextBoxComponent oneVOneTime;
    private TextBoxComponent oneVOneWinHp;
    private TextBoxComponent twoVTwoTime;
    private TextBoxComponent twoVTwoWinHp;
    private TextBoxComponent offensePerDamage;
    private TextBoxComponent supportPerHeal;
    private TextBoxComponent defensePerBlocked;
    private TextBoxComponent killBonus;
    private TextBoxComponent outOfBoundsDamage;
    private TextBoxComponent outOfBoundsPoints;

    private LabelComponent errorLabel;
    private SyncMatchmakingSettingsPayload pendingSettings;

    public MatchmakingAdminScreen() {
        super(Component.translatable("duels_ld.screen.matchmaking_admin.title"));
    }

    @Override
    protected @NotNull OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, Containers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(Surface.flat(BG));
        root.alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER);

        int shellWidth = Math.max(340, Math.min(420, this.width - 24));
        int shellHeight = Math.max(260, Math.min(360, this.height - 24));
        FlowLayout shell = Containers.verticalFlow(Sizing.fixed(shellWidth), Sizing.fixed(shellHeight));
        shell.surface(Surface.flat(PANEL).and(Surface.outline(HAIRLINE_HI)));

        shell.child(buildHeader());

        FlowLayout content = Containers.verticalFlow(Sizing.fill(100), Sizing.content());
        content.surface(Surface.flat(PANEL));
        content.padding(Insets.of(10));
        content.gap(4);

        content.child(sectionHeader(Component.translatable("duels_ld.screen.matchmaking_admin.title")));
        content.child(spacer(2));
        oneVOneTime = settingRow(content, "duels_ld.screen.matchmaking_admin.one_v_one_time_label");
        oneVOneWinHp = settingRow(content, "duels_ld.screen.matchmaking_admin.one_v_one_win_hp_label");
        twoVTwoTime = settingRow(content, "duels_ld.screen.matchmaking_admin.two_v_two_time_label");
        twoVTwoWinHp = settingRow(content, "duels_ld.screen.matchmaking_admin.two_v_two_win_hp_label");

        content.child(spacer(8));
        offensePerDamage = settingRow(content, "duels_ld.screen.matchmaking_admin.offense_label");
        supportPerHeal = settingRow(content, "duels_ld.screen.matchmaking_admin.support_label");
        defensePerBlocked = settingRow(content, "duels_ld.screen.matchmaking_admin.defense_label");
        killBonus = settingRow(content, "duels_ld.screen.matchmaking_admin.kill_bonus_label");

        content.child(spacer(8));
        content.child(sectionHeader(Component.translatable("duels_ld.screen.matchmaking_admin.penalties_header")));
        content.child(spacer(2));
        outOfBoundsDamage = settingRow(content, "duels_ld.screen.matchmaking_admin.out_of_bounds_damage_label");
        outOfBoundsPoints = settingRow(content, "duels_ld.screen.matchmaking_admin.out_of_bounds_points_label");

        ScrollContainer<FlowLayout> scroll = Containers.verticalScroll(Sizing.fill(100), Sizing.expand(), content);
        scroll.surface(Surface.flat(PANEL));
        scroll.scrollbar(ScrollContainer.Scrollbar.vanillaFlat());
        scroll.scrollbarThiccness(8);
        scroll.scrollStep(18);
        shell.child(scroll);

        shell.child(buildFooter());
        root.child(shell);

        if (pendingSettings != null) {
            applySettings(pendingSettings);
            pendingSettings = null;
        }
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

        header.child(text(Component.translatable("duels_ld.screen.matchmaking_admin.title"), INK));
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

        errorLabel = Components.label(Component.empty());
        errorLabel.color(Color.ofArgb(DANGER));
        errorLabel.horizontalSizing(Sizing.expand());
        footer.child(errorLabel);

        ButtonComponent save = styledButton(
            Component.translatable("duels_ld.screen.matchmaking_admin.save"), ACCENT_DARK, ACCENT, ACCENT, b -> save());
        save.horizontalSizing(Sizing.fixed(80));
        footer.child(save);
        return footer;
    }

    public void applySettings(SyncMatchmakingSettingsPayload payload) {
        if (oneVOneTime == null) {
            pendingSettings = payload;
            return;
        }
        oneVOneTime.text(Integer.toString(payload.oneVOneDuration()));
        oneVOneWinHp.text(Integer.toString(payload.oneVOneWinHp()));
        twoVTwoTime.text(Integer.toString(payload.twoVTwoDuration()));
        twoVTwoWinHp.text(Integer.toString(payload.twoVTwoWinHp()));
        offensePerDamage.text(Double.toString(payload.offensePerDamage()));
        supportPerHeal.text(Double.toString(payload.supportPerHeal()));
        defensePerBlocked.text(Double.toString(payload.defensePerBlocked()));
        killBonus.text(Double.toString(payload.killBonus()));
        outOfBoundsDamage.text(Double.toString(payload.outOfBoundsDamagePercent()));
        outOfBoundsPoints.text(Double.toString(payload.outOfBoundsPointPenaltyPercent()));
    }

    private void save() {
        int v1Time = parseInt(oneVOneTime.getValue());
        int v1Hp = parseInt(oneVOneWinHp.getValue());
        int v2Time = parseInt(twoVTwoTime.getValue());
        int v2Hp = parseInt(twoVTwoWinHp.getValue());
        double off = parseDouble(offensePerDamage.getValue());
        double sup = parseDouble(supportPerHeal.getValue());
        double def = parseDouble(defensePerBlocked.getValue());
        double kill = parseDouble(killBonus.getValue());
        double oobDmg = parseDouble(outOfBoundsDamage.getValue());
        double oobPts = parseDouble(outOfBoundsPoints.getValue());

        if (v1Time < 10 || v2Time < 10) {
            setError("duels_ld.screen.matchmaking_admin.error_time_min");
            return;
        }
        if (v1Hp < 0 || v1Hp > 100 || v2Hp < 0 || v2Hp > 100) {
            setError("duels_ld.screen.matchmaking_admin.error_win_hp_range");
            return;
        }
        if (off < 0 || sup < 0 || def < 0 || kill < 0) {
            setError("duels_ld.screen.matchmaking_admin.error_points_non_negative");
            return;
        }
        if (oobDmg < 0 || oobDmg > 100 || oobPts < 0 || oobPts > 100) {
            setError("duels_ld.screen.matchmaking_admin.error_percent_range");
            return;
        }

        if (errorLabel != null) {
            errorLabel.text(Component.empty());
        }
        ClientPlayNetworking.send(new UpdateMatchmakingSettingsPayload(
            v1Time, v1Hp, v2Time, v2Hp, off, sup, def, kill, oobDmg, oobPts
        ));
    }

    private void setError(String key) {
        if (errorLabel != null) {
            errorLabel.text(Component.translatable(key));
        }
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private TextBoxComponent settingRow(FlowLayout parent, String captionKey) {
        FlowLayout row = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(22));
        row.gap(6);
        row.alignment(HorizontalAlignment.LEFT, VerticalAlignment.CENTER);

        LabelComponent caption = Components.label(Component.translatable(captionKey));
        caption.color(Color.ofArgb(INK_MID));
        caption.horizontalSizing(Sizing.expand());
        row.child(caption);

        FlowLayout fieldWrap = Containers.horizontalFlow(Sizing.fixed(90), Sizing.fixed(20));
        fieldWrap.surface(Surface.flat(PANEL_2).and(Surface.outline(HAIRLINE)));
        fieldWrap.alignment(HorizontalAlignment.LEFT, VerticalAlignment.CENTER);
        FlowLayout accent = Containers.verticalFlow(Sizing.fixed(2), Sizing.fill(100));
        accent.surface(Surface.flat(ACCENT));
        fieldWrap.child(accent);

        TextBoxComponent field = Components.textBox(Sizing.expand(), "");
        field.verticalSizing(Sizing.fixed(16));
        fieldWrap.child(field);
        row.child(fieldWrap);

        parent.child(row);
        return field;
    }

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
}
