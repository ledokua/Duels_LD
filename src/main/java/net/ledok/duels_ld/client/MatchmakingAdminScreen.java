package net.ledok.duels_ld.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.duels_ld.network.SyncMatchmakingSettingsPayload;
import net.ledok.duels_ld.network.UpdateMatchmakingSettingsPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class MatchmakingAdminScreen extends Screen {
    private static final int PANEL_WIDTH = 260;
    private static final int PANEL_HEIGHT = 180;
    private static final ResourceLocation LOBBY_BG = ResourceLocation.fromNamespaceAndPath("duels_ld", "textures/gui/lobby_main.png");

    private EditBox oneVOneTime;
    private EditBox oneVOneWinHp;
    private EditBox twoVTwoTime;
    private EditBox twoVTwoWinHp;
    private EditBox offensePerDamage;
    private EditBox supportPerHeal;
    private EditBox defensePerBlocked;
    private EditBox killBonus;
    private Component errorMessage = Component.empty();
    private SyncMatchmakingSettingsPayload pendingSettings;

    public MatchmakingAdminScreen() {
        super(Component.translatable("duels_ld.screen.matchmaking_admin.title"));
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        int labelX = left + 12;
        int fieldX = left + 165;
        int rowHeight = 18;

        oneVOneTime = new EditBox(this.font, fieldX, top + 24, 70, 16, Component.translatable("duels_ld.screen.matchmaking_admin.one_v_one_time_placeholder"));
        oneVOneWinHp = new EditBox(this.font, fieldX, top + 24 + rowHeight, 70, 16, Component.translatable("duels_ld.screen.matchmaking_admin.one_v_one_win_hp_placeholder"));
        twoVTwoTime = new EditBox(this.font, fieldX, top + 24 + rowHeight * 2, 70, 16, Component.translatable("duels_ld.screen.matchmaking_admin.two_v_two_time_placeholder"));
        twoVTwoWinHp = new EditBox(this.font, fieldX, top + 24 + rowHeight * 3, 70, 16, Component.translatable("duels_ld.screen.matchmaking_admin.two_v_two_win_hp_placeholder"));

        offensePerDamage = new EditBox(this.font, fieldX, top + 24 + rowHeight * 4, 70, 16, Component.translatable("duels_ld.screen.matchmaking_admin.offense_placeholder"));
        supportPerHeal = new EditBox(this.font, fieldX, top + 24 + rowHeight * 5, 70, 16, Component.translatable("duels_ld.screen.matchmaking_admin.support_placeholder"));
        defensePerBlocked = new EditBox(this.font, fieldX, top + 24 + rowHeight * 6, 70, 16, Component.translatable("duels_ld.screen.matchmaking_admin.defense_placeholder"));
        killBonus = new EditBox(this.font, fieldX, top + 24 + rowHeight * 7, 70, 16, Component.translatable("duels_ld.screen.matchmaking_admin.kill_bonus_placeholder"));

        addRenderableWidget(oneVOneTime);
        addRenderableWidget(oneVOneWinHp);
        addRenderableWidget(twoVTwoTime);
        addRenderableWidget(twoVTwoWinHp);
        addRenderableWidget(offensePerDamage);
        addRenderableWidget(supportPerHeal);
        addRenderableWidget(defensePerBlocked);
        addRenderableWidget(killBonus);

        addRenderableWidget(new LabelWidget(labelX, top + 26, Component.translatable("duels_ld.screen.matchmaking_admin.one_v_one_time_label")));
        addRenderableWidget(new LabelWidget(labelX, top + 26 + rowHeight, Component.translatable("duels_ld.screen.matchmaking_admin.one_v_one_win_hp_label")));
        addRenderableWidget(new LabelWidget(labelX, top + 26 + rowHeight * 2, Component.translatable("duels_ld.screen.matchmaking_admin.two_v_two_time_label")));
        addRenderableWidget(new LabelWidget(labelX, top + 26 + rowHeight * 3, Component.translatable("duels_ld.screen.matchmaking_admin.two_v_two_win_hp_label")));
        addRenderableWidget(new LabelWidget(labelX, top + 26 + rowHeight * 4, Component.translatable("duels_ld.screen.matchmaking_admin.offense_label")));
        addRenderableWidget(new LabelWidget(labelX, top + 26 + rowHeight * 5, Component.translatable("duels_ld.screen.matchmaking_admin.support_label")));
        addRenderableWidget(new LabelWidget(labelX, top + 26 + rowHeight * 6, Component.translatable("duels_ld.screen.matchmaking_admin.defense_label")));
        addRenderableWidget(new LabelWidget(labelX, top + 26 + rowHeight * 7, Component.translatable("duels_ld.screen.matchmaking_admin.kill_bonus_label")));

        addRenderableWidget(Button.builder(Component.translatable("duels_ld.screen.matchmaking_admin.save"), button -> save())
            .bounds(left + 12, top + PANEL_HEIGHT - 26, 70, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("duels_ld.screen.matchmaking_admin.close"), button -> this.onClose())
            .bounds(left + PANEL_WIDTH - 82, top + PANEL_HEIGHT - 26, 70, 18).build());

        if (pendingSettings != null) {
            applySettings(pendingSettings);
            pendingSettings = null;
        }
    }

    public void applySettings(SyncMatchmakingSettingsPayload payload) {
        if (oneVOneTime == null) {
            pendingSettings = payload;
            return;
        }
        oneVOneTime.setValue(Integer.toString(payload.oneVOneDuration()));
        oneVOneWinHp.setValue(Integer.toString(payload.oneVOneWinHp()));
        twoVTwoTime.setValue(Integer.toString(payload.twoVTwoDuration()));
        twoVTwoWinHp.setValue(Integer.toString(payload.twoVTwoWinHp()));
        offensePerDamage.setValue(Double.toString(payload.offensePerDamage()));
        supportPerHeal.setValue(Double.toString(payload.supportPerHeal()));
        defensePerBlocked.setValue(Double.toString(payload.defensePerBlocked()));
        killBonus.setValue(Double.toString(payload.killBonus()));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        guiGraphics.blit(LOBBY_BG, left, top, 0, 0, PANEL_WIDTH, PANEL_HEIGHT, PANEL_WIDTH, PANEL_HEIGHT);
        guiGraphics.drawCenteredString(this.font, this.title, left + PANEL_WIDTH / 2, top + 3, 0xFF000000);

        if (!errorMessage.getString().isEmpty()) {
            guiGraphics.drawString(this.font, errorMessage, left + 12, top + PANEL_HEIGHT - 40, 0xFFFF6666);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
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

        if (v1Time < 10 || v2Time < 10) {
            errorMessage = Component.translatable("duels_ld.screen.matchmaking_admin.error_time_min");
            return;
        }
        if (v1Hp < 0 || v1Hp > 100 || v2Hp < 0 || v2Hp > 100) {
            errorMessage = Component.translatable("duels_ld.screen.matchmaking_admin.error_win_hp_range");
            return;
        }
        if (off < 0 || sup < 0 || def < 0 || kill < 0) {
            errorMessage = Component.translatable("duels_ld.screen.matchmaking_admin.error_points_non_negative");
            return;
        }

        errorMessage = Component.empty();
        ClientPlayNetworking.send(new UpdateMatchmakingSettingsPayload(
            v1Time, v1Hp, v2Time, v2Hp, off, sup, def, kill
        ));
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

    private class LabelWidget extends net.minecraft.client.gui.components.AbstractWidget {
        public LabelWidget(int x, int y, Component text) {
            super(x, y, 130, 16, text);
            this.active = false;
            this.visible = true;
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            guiGraphics.drawString(MatchmakingAdminScreen.this.font, this.getMessage(), this.getX(), this.getY(), 0xFFFFFFFF);
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
        }
    }
}
