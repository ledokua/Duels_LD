package net.ledok.duels_ld.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.duels_ld.network.JoinQueuePayload;
import net.ledok.duels_ld.network.LeaveQueuePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class MatchmakingScreen extends Screen {
    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_HEIGHT = 180;
    private static final ResourceLocation LOBBY_BG = ResourceLocation.fromNamespaceAndPath("duels_ld", "textures/gui/lobby_main.png");

    public MatchmakingScreen() {
        super(Component.literal("Matchmaking"));
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        this.addRenderableWidget(Button.builder(Component.literal("Queue 1v1"), button -> {
            ClientPlayNetworking.send(new JoinQueuePayload(JoinQueuePayload.MODE_1V1));
        }).bounds(left + 12, top + 28, 90, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Queue 2v2"), button -> {
            ClientPlayNetworking.send(new JoinQueuePayload(JoinQueuePayload.MODE_2V2));
        }).bounds(left + 12, top + 52, 90, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Leave Queue"), button -> {
            ClientPlayNetworking.send(new LeaveQueuePayload());
        }).bounds(left + 12, top + 76, 90, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Close"), button -> this.onClose())
            .bounds(left + 12, top + PANEL_HEIGHT - 30, 90, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        guiGraphics.blit(LOBBY_BG, left, top, 0, 0, PANEL_WIDTH, PANEL_HEIGHT, PANEL_WIDTH, PANEL_HEIGHT);
        guiGraphics.drawCenteredString(this.font, this.title, left + PANEL_WIDTH / 2, top + 3, 0xFF000000);

        guiGraphics.drawString(this.font, "Pick a queue:", left + 120, top + 32, 0xFFEAEAEA);
        guiGraphics.drawString(this.font, "You can queue", left + 120, top + 44, 0xFFEAEAEA);
        guiGraphics.drawString(this.font, "in both modes.", left + 120, top + 56, 0xFFEAEAEA);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
