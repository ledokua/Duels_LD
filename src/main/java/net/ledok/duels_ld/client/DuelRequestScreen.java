package net.ledok.duels_ld.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.duels_ld.network.AcceptRequestPayload;
import net.ledok.duels_ld.network.DeclineRequestPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DuelRequestScreen extends Screen {
    private RequestList requestList;
    private final List<RequestEntryData> requests = new ArrayList<>();

    public DuelRequestScreen() {
        super(Component.literal("Duel Requests"));
    }

    public void setRequests(List<RequestEntryData> newRequests) {
        this.requests.clear();
        this.requests.addAll(newRequests);
        if (this.requestList != null) {
            this.requestList.refresh();
        }
    }

    @Override
    protected void init() {
        this.requestList = new RequestList(this.minecraft, this.width, this.height, 32, this.height - 32, 25);
        this.requestList.refresh();
        this.addRenderableWidget(this.requestList);
        
        this.addRenderableWidget(Button.builder(Component.literal("Close"), button -> this.onClose())
            .bounds(this.width / 2 - 100, this.height - 25, 200, 20)
            .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        this.requestList.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    public static class RequestEntryData {
        public final UUID senderId;
        public final String senderName;
        public final String settingsDesc;

        public RequestEntryData(UUID senderId, String senderName, String settingsDesc) {
            this.senderId = senderId;
            this.senderName = senderName;
            this.settingsDesc = settingsDesc;
        }
    }

    class RequestList extends ObjectSelectionList<RequestList.RequestEntry> {
        public RequestList(net.minecraft.client.Minecraft minecraft, int width, int height, int y0, int y1, int itemHeight) {
            super(minecraft, width, height, y0, itemHeight);
        }

        public void refresh() {
            this.clearEntries();
            for (RequestEntryData data : requests) {
                this.addEntry(new RequestEntry(data));
            }
        }

        class RequestEntry extends ObjectSelectionList.Entry<RequestEntry> {
            private final RequestEntryData data;
            private final Button acceptButton;
            private final Button declineButton;

            public RequestEntry(RequestEntryData data) {
                this.data = data;
                this.acceptButton = Button.builder(Component.literal("Accept"), button -> {
                    ClientPlayNetworking.send(new AcceptRequestPayload(data.senderId));
                    DuelRequestScreen.this.onClose();
                }).bounds(0, 0, 60, 20).build();
                
                this.declineButton = Button.builder(Component.literal("Decline"), button -> {
                    ClientPlayNetworking.send(new DeclineRequestPayload(data.senderId));
                    requests.remove(data);
                    refresh();
                }).bounds(0, 0, 60, 20).build();
            }

            @Override
            public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
                guiGraphics.drawString(DuelRequestScreen.this.font, data.senderName, left + 5, top + 2, 0xFFFFFF);
                guiGraphics.drawString(DuelRequestScreen.this.font, data.settingsDesc, left + 5, top + 12, 0xAAAAAA);
                
                this.acceptButton.setX(left + width - 135);
                this.acceptButton.setY(top);
                this.acceptButton.render(guiGraphics, mouseX, mouseY, partialTick);
                
                this.declineButton.setX(left + width - 70);
                this.declineButton.setY(top);
                this.declineButton.render(guiGraphics, mouseX, mouseY, partialTick);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (this.acceptButton.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
                if (this.declineButton.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }

            @Override
            public Component getNarration() {
                return Component.literal("Request from " + data.senderName);
            }
        }
    }
}
