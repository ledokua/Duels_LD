package net.ledok.duels_ld.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.duels_ld.network.SyncMatchmakingSettingsPayload;
import net.ledok.duels_ld.network.UpdateMatchmakingSettingsPayload;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin settings form, styled to match the Arenas_LD raid/dungeon screens (flat dark theme).
 * Edits duration / win-HP / scoring weights, the extra battle health and the out-of-bounds penalty.
 */
public class MatchmakingAdminScreen extends DuelsScreen {

    private static final int BG = 0xFF070E14;

    // Field text by lang key, kept across rebuilds (window resize) so typed values survive.
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, TextField> fields = new LinkedHashMap<>();
    private Label errorLabel;
    private Component error = Component.empty();

    public MatchmakingAdminScreen() {
        super(Component.translatable("duels_ld.screen.matchmaking_admin.title"), UiTheme.MODERN, 340, 420, 260, 360, BG);
    }

    @Override
    protected void build(Flex shell) {
        fields.clear();
        Flex info = Flex.column();
        info.item(text(Component.translatable("duels_ld.screen.matchmaking_admin.title"), T.ink));
        Flex header = header(info);
        header.item(closeButton());
        shell.item(header);

        Flex content = scrollBody(shell, Insets.of(10), 4);
        content.item(sectionHeader(Component.translatable("duels_ld.screen.matchmaking_admin.title")));
        content.item(spacer(2));
        settingRow(content, ONE_TIME);
        settingRow(content, ONE_HP);
        settingRow(content, TWO_TIME);
        settingRow(content, TWO_HP);

        content.item(spacer(8));
        settingRow(content, OFFENSE);
        settingRow(content, SUPPORT);
        settingRow(content, DEFENSE);
        settingRow(content, KILL);

        content.item(spacer(8));
        content.item(sectionHeader(Component.translatable("duels_ld.screen.matchmaking_admin.battle_header")));
        content.item(spacer(2));
        settingRow(content, EXTRA_HP);

        content.item(spacer(8));
        content.item(sectionHeader(Component.translatable("duels_ld.screen.matchmaking_admin.penalties_header")));
        content.item(spacer(2));
        settingRow(content, OOB_DAMAGE);
        settingRow(content, OOB_POINTS);

        Flex footer = Flex.row().gap(6).padding(Insets.of(6)).alignItems(Align.CENTER);
        footer.sizing(Sizing.fill(), Sizing.fixed(30));
        footer.background(T.bar());
        errorLabel = Label.of(100, error).color(T.danger);
        errorLabel.sizing(Sizing.expand(), Sizing.content());
        footer.item(errorLabel);
        footer.item(button(Component.translatable("duels_ld.screen.matchmaking_admin.save"), 80, 18,
            T.accentDark, T.accent, T.accent, this::save));
        shell.item(footer);
    }

    private static final String P = "duels_ld.screen.matchmaking_admin.";
    private static final String ONE_TIME = P + "one_v_one_time_label";
    private static final String ONE_HP = P + "one_v_one_win_hp_label";
    private static final String TWO_TIME = P + "two_v_two_time_label";
    private static final String TWO_HP = P + "two_v_two_win_hp_label";
    private static final String OFFENSE = P + "offense_label";
    private static final String SUPPORT = P + "support_label";
    private static final String DEFENSE = P + "defense_label";
    private static final String KILL = P + "kill_bonus_label";
    private static final String EXTRA_HP = P + "extra_health_label";
    private static final String OOB_DAMAGE = P + "out_of_bounds_damage_label";
    private static final String OOB_POINTS = P + "out_of_bounds_points_label";

    /** Fills the fields from the server's settings (they may arrive before or after the screen opens). */
    public void applySettings(SyncMatchmakingSettingsPayload payload) {
        values.put(ONE_TIME, Integer.toString(payload.oneVOneDuration()));
        values.put(ONE_HP, Integer.toString(payload.oneVOneWinHp()));
        values.put(TWO_TIME, Integer.toString(payload.twoVTwoDuration()));
        values.put(TWO_HP, Integer.toString(payload.twoVTwoWinHp()));
        values.put(OFFENSE, Double.toString(payload.offensePerDamage()));
        values.put(SUPPORT, Double.toString(payload.supportPerHeal()));
        values.put(DEFENSE, Double.toString(payload.defensePerBlocked()));
        values.put(KILL, Double.toString(payload.killBonus()));
        values.put(OOB_DAMAGE, Double.toString(payload.outOfBoundsDamagePercent()));
        values.put(OOB_POINTS, Double.toString(payload.outOfBoundsPointPenaltyPercent()));
        values.put(EXTRA_HP, Double.toString(payload.extraHealth()));
        fields.forEach((key, field) -> field.text(values.getOrDefault(key, "")));
    }

    private void save() {
        int v1Time = parseInt(value(ONE_TIME));
        int v1Hp = parseInt(value(ONE_HP));
        int v2Time = parseInt(value(TWO_TIME));
        int v2Hp = parseInt(value(TWO_HP));
        double off = parseDouble(value(OFFENSE));
        double sup = parseDouble(value(SUPPORT));
        double def = parseDouble(value(DEFENSE));
        double kill = parseDouble(value(KILL));
        double oobDmg = parseDouble(value(OOB_DAMAGE));
        double oobPts = parseDouble(value(OOB_POINTS));
        double extraHp = parseDouble(value(EXTRA_HP));

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
        // 1004 keeps base 20 + bonus within the max_health attribute cap of 1024.
        if (extraHp < 0 || extraHp > 1004) {
            setError("duels_ld.screen.matchmaking_admin.error_extra_health_range");
            return;
        }

        showError(Component.empty());
        ClientPlayNetworking.send(new UpdateMatchmakingSettingsPayload(
            v1Time, v1Hp, v2Time, v2Hp, off, sup, def, kill, oobDmg, oobPts, extraHp
        ));
    }

    private void setError(String key) {
        showError(Component.translatable(key));
    }

    private void showError(Component text) {
        error = text;
        if (errorLabel != null) {
            errorLabel.text(text);
        }
    }

    private String value(String key) {
        return values.getOrDefault(key, "");
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

    private void settingRow(Flex parent, String captionKey) {
        Flex row = Flex.row().gap(6).alignItems(Align.CENTER);
        row.sizing(Sizing.fill(), Sizing.fixed(22));

        Label caption = Label.of(100, Component.translatable(captionKey)).color(T.inkMid);
        caption.sizing(Sizing.expand(), Sizing.content());
        row.item(caption);

        TextField field = new TextField(90, values.getOrDefault(captionKey, ""), text -> values.put(captionKey, text));
        field.size(90, 18);
        row.item(field);
        fields.put(captionKey, field);

        parent.item(row);
    }
}
