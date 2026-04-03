package net.ledok.duels_ld.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.duels_ld.network.JoinQueuePayload;
import net.ledok.duels_ld.network.LeaveQueuePayload;
import net.ledok.duels_ld.network.PartyAcceptPayload;
import net.ledok.duels_ld.network.PartyInvitePayload;
import net.ledok.duels_ld.network.RequestEloPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class MatchmakingScreen extends Screen {
    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_HEIGHT = 180;
    private static final ResourceLocation LOBBY_BG = ResourceLocation.fromNamespaceAndPath("duels_ld", "textures/gui/lobby_main.png");
    private static long queuedAt1v1 = -1;
    private static long queuedAt2v2 = -1;
    private long queue1v1Start = -1;
    private long queue2v2Start = -1;
    private int elo1v1 = 0;
    private int elo2v2 = 0;
    private EditBox inviteField;
    private EditBox acceptField;

    public MatchmakingScreen() {
        super(Component.translatable("duels_ld.screen.matchmaking.title"));
    }

    @Override
    protected void init() {
        ClientPlayNetworking.send(new RequestEloPayload());
        if (this.minecraft != null) {
            this.minecraft.getTextureManager().getTexture(LOBBY_BG).setFilter(false, false);
        }

        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        queue1v1Start = queuedAt1v1;
        queue2v2Start = queuedAt2v2;

        this.addRenderableWidget(Button.builder(Component.translatable("duels_ld.screen.matchmaking.queue_1v1"), button -> {
            ClientPlayNetworking.send(new JoinQueuePayload(JoinQueuePayload.MODE_1V1));
            if (queue1v1Start < 0) {
                queue1v1Start = System.currentTimeMillis();
                queuedAt1v1 = queue1v1Start;
            }
        }).bounds(left + 12, top + 28, 90, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("duels_ld.screen.matchmaking.queue_2v2"), button -> {
            ClientPlayNetworking.send(new JoinQueuePayload(JoinQueuePayload.MODE_2V2));
            if (queue2v2Start < 0) {
                queue2v2Start = System.currentTimeMillis();
                queuedAt2v2 = queue2v2Start;
            }
        }).bounds(left + 12, top + 52, 90, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("duels_ld.screen.matchmaking.leave_queue"), button -> {
            ClientPlayNetworking.send(new LeaveQueuePayload());
            queuedAt1v1 = -1;
            queuedAt2v2 = -1;
            queue1v1Start = -1;
            queue2v2Start = -1;
        }).bounds(left + 12, top + 76, 90, 20).build());

        this.inviteField = new EditBox(this.font, left + 132, top + 92, 90, 16, Component.translatable("duels_ld.screen.matchmaking.invite_placeholder"));
        this.acceptField = new EditBox(this.font, left + 132, top + 130, 90, 16, Component.translatable("duels_ld.screen.matchmaking.accept_placeholder"));
        this.addRenderableWidget(this.inviteField);
        this.addRenderableWidget(this.acceptField);

        this.addRenderableWidget(Button.builder(Component.translatable("duels_ld.screen.matchmaking.invite"), button -> {
            String name = inviteField.getValue().trim();
            if (!name.isEmpty()) {
                ClientPlayNetworking.send(new PartyInvitePayload(name));
            }
        }).bounds(left + 132, top + 110, 90, 16).build());

        this.addRenderableWidget(Button.builder(Component.translatable("duels_ld.screen.matchmaking.accept"), button -> {
            String name = acceptField.getValue().trim();
            ClientPlayNetworking.send(new PartyAcceptPayload(name));
        }).bounds(left + 132, top + 148, 90, 16).build());

        this.addRenderableWidget(Button.builder(Component.translatable("duels_ld.screen.matchmaking.close"), button -> this.onClose())
            .bounds(left + 12, top + PANEL_HEIGHT - 30, 90, 20).build());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (inviteField != null && inviteField.isFocused() && keyCode == 258) { // Tab
            applyCompletion(inviteField);
            return true;
        }
        if (acceptField != null && acceptField.isFocused() && keyCode == 258) { // Tab
            applyCompletion(acceptField);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        guiGraphics.blit(LOBBY_BG, left, top, 0, 0, PANEL_WIDTH, PANEL_HEIGHT, PANEL_WIDTH, PANEL_HEIGHT);
        guiGraphics.drawCenteredString(this.font, this.title, left + PANEL_WIDTH / 2, top + 3, 0xFF000000);

        guiGraphics.drawString(this.font, Component.translatable("duels_ld.screen.matchmaking.elo_1v1", elo1v1), left + 120, top + 26, 0xFFEAEAEA);
        guiGraphics.drawString(this.font, Component.translatable("duels_ld.screen.matchmaking.elo_2v2", elo2v2), left + 120, top + 38, 0xFFEAEAEA);
        guiGraphics.drawString(this.font, Component.translatable("duels_ld.screen.matchmaking.elo_party", elo2v2), left + 120, top + 50, 0xFFEAEAEA);

        guiGraphics.drawString(this.font, Component.translatable("duels_ld.screen.matchmaking.queue_time_1v1", formatElapsed(queue1v1Start)), left + 12, top + 100, 0xFFEAEAEA);
        guiGraphics.drawString(this.font, Component.translatable("duels_ld.screen.matchmaking.queue_time_2v2", formatElapsed(queue2v2Start)), left + 12, top + 112, 0xFFEAEAEA);

        guiGraphics.drawString(this.font, Component.translatable("duels_ld.screen.matchmaking.party_invite_label"), left + 132, top + 78, 0xFFEAEAEA);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // No background blur.
    }

    public void setElo(int oneVOne, int twoVTwo) {
        this.elo1v1 = oneVOne;
        this.elo2v2 = twoVTwo;
    }

    private String formatElapsed(long startMs) {
        if (startMs < 0) {
            return Component.translatable("duels_ld.screen.matchmaking.queue_time_none").getString();
        }
        long elapsed = (System.currentTimeMillis() - startMs) / 1000;
        long minutes = elapsed / 60;
        long seconds = elapsed % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private void applyCompletion(EditBox field) {
        if (minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        String current = field.getValue().trim();
        String prefix = current.toLowerCase();
        String best = null;
        for (var info : minecraft.getConnection().getOnlinePlayers()) {
            String name = info.getProfile().getName();
            if (name.toLowerCase().startsWith(prefix)) {
                if (best == null || name.length() < best.length()) {
                    best = name;
                }
            }
        }
        if (best != null) {
            field.setValue(best);
        }
    }
}
