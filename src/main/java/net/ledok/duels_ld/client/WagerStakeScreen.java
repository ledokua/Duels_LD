package net.ledok.duels_ld.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.duels_ld.network.WagerSubmitPayload;
import net.ledok.duels_ld.wager.WagerItems;
import net.ledok.duels_ld.wager.WagerStakeMenu;
import net.ledok.vectorlib.client.canvas.CanvasRenderContext;
import net.ledok.vectorlib.client.canvas.ItemStackNode;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.VectorCanvas;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.NumberField;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.ledok.vectorlib.client.presentation.CanvasHandledScreen;
import net.ledok.vectorlib.client.presentation.Placement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The stake screen: put up a listing (or a personal challenge), or offer on someone's listing.
 * Items go into the stake slots like any container; nothing is staked until "Post" / "Offer" is
 * confirmed, and the server checks everything again. Banned items and filled containers show red
 * in the inventory and can't go into the slots.
 */
public class WagerStakeScreen extends CanvasHandledScreen<WagerStakeMenu> {
    private static final float W = 460;
    private static final float H = 340;
    private static final float LEFT = 196;

    private UiTheme T = UiTheme.current();
    private final WagerStakeMenu.Data data;
    private final long openedAt = System.currentTimeMillis();

    private long coins;
    private String comment = "";
    private Component status = Component.empty();
    private boolean statusError;
    private boolean waiting;

    private Label statusLabel;
    private Button submit;
    private TextNode timer;
    private TextNode balanceLine;

    public WagerStakeScreen(WagerStakeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, VectorCanvas.create(W, H), Placement.Screen.center());
        this.data = menu.data();
        this.coins = data.fixedCoins() > 0 ? data.fixedCoins() : 0;
        canvas.theme(T.theme());
        dimBackground(true);
    }

    @Override
    protected void init() {
        super.init();
        float scale = Math.min(1f, Math.min((width - 8) / W, (height - 8) / H));
        placement(Placement.Screen.center().scale(scale));
        build();
    }

    /** Never treat an off-panel click as "throw the carried item". */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top, int button) {
        return false;
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    private void build() {
        canvas.clear();
        Flex shell = canvas.add(Flex.column());
        shell.sizing(Sizing.fixed(W), Sizing.fixed(H));
        shell.background(T.shell());
        shell.padding(Insets.of(T.shellPadding()));

        shell.item(header());

        Flex body = Flex.row().gap(10).padding(Insets.of(8, 10, 6, 10));
        body.sizing(Sizing.fill(), Sizing.expand());
        body.background(T.content());
        body.item(leftColumn());
        body.item(rightColumn());
        shell.item(body);

        shell.item(inventory());
        shell.item(footer());
        shell.layoutIn(W, H);
        refreshState();
    }

    private Flex header() {
        Flex header = Flex.row().gap(8).padding(Insets.of(6, 10, 6, 10)).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.fixed(30));
        header.background(T.bar());
        Flex mark = Flex.column();
        mark.sizing(Sizing.fixed(14), Sizing.fixed(14));
        mark.backgroundFill(T.accent, T.accentDark, 1);
        header.item(mark);
        header.item(WagerUi.text(title, T.ink));
        header.spacer();
        header.item(WagerUi.button(T, Component.literal("×"), 20, 16, T.panel, T.accent, T.hairline, this::onClose));
        return header;
    }

    private Flex leftColumn() {
        Flex col = Flex.column().gap(3);
        col.sizing(Sizing.fixed(LEFT), Sizing.content());

        if (data.offer()) {
            col.item(WagerUi.text(Component.translatable("duels_ld.wager.screen.their_stake", data.authorName()), T.inkDim));
            col.item(WagerUi.stakeRow(T, data.authorItems(), 0));
            if (data.authorCoins() > 0) {
                col.item(WagerUi.text(Component.translatable("duels_ld.wager.stake.coins", data.authorCoins()), T.gold));
            }
            if (data.authorItems().isEmpty() && data.authorCoins() <= 0) {
                col.item(WagerUi.text(Component.literal("—"), T.inkDim));
            }
            col.item(WagerUi.spacer(4));
        } else if (!data.targetName().isEmpty()) {
            col.item(WagerUi.text(Component.translatable("duels_ld.wager.screen.challenge_for", data.targetName()), T.accent));
        }

        col.item(WagerUi.text(Component.translatable("duels_ld.wager.screen.your_stake"), T.inkDim));
        Flex slots = Flex.row().gap(2);
        slots.sizing(Sizing.content(), Sizing.fixed(WagerUi.SLOT));
        for (int i = 0; i < menu.stakeSlots(); i++) {
            slots.item(new SlotWidget(i, false));
        }
        col.item(slots);

        // Coins: fixed to the author's on a listing with coins, else free (optional).
        Flex coinRow = Flex.row().gap(6).alignItems(Align.CENTER);
        coinRow.sizing(Sizing.fill(), Sizing.fixed(20));
        coinRow.item(WagerUi.text(Component.translatable("duels_ld.wager.screen.coins"), T.inkMid));
        if (!data.coinsEnabled()) {
            coinRow.item(WagerUi.text(Component.translatable("duels_ld.wager.screen.coins_off"), T.inkDim));
        } else if (data.fixedCoins() > 0) {
            coinRow.item(WagerUi.text(Component.translatable("duels_ld.wager.screen.coins_fixed", data.fixedCoins()), T.gold));
        } else {
            long max = data.maxCoins() > 0 ? Math.min(data.maxCoins(), data.balance()) : data.balance();
            NumberField field = new NumberField(80, coins, v -> {
                coins = v;
                refreshState();
            });
            field.range(0, Math.max(0, max));
            field.size(80, 16);
            coinRow.item(field);
        }
        col.item(coinRow);
        balanceLine = WagerUi.text(Component.empty(), T.inkDim);
        col.item(balanceLine);

        if (!data.offer()) {
            col.item(WagerUi.spacer(2));
            col.item(WagerUi.text(Component.translatable("duels_ld.wager.screen.comment"), T.inkMid));
            TextField field = new TextField(LEFT, "", text -> comment = text);
            field.maxLength(data.commentMax());
            field.text(comment);
            field.hint(Component.translatable("duels_ld.wager.screen.comment_hint").getString());
            field.size(LEFT, 16);
            col.item(field);
        }
        return col;
    }

    private Flex rightColumn() {
        Flex col = Flex.column().gap(5);
        col.sizing(Sizing.expand(), Sizing.content());
        float width = W - LEFT - 30 - 2 * T.shellPadding();
        if (data.offer()) {
            if (!data.authorComment().isEmpty()) {
                col.item(WagerUi.wrapped(Component.translatable("duels_ld.wager.screen.author_comment", data.authorComment()), T.inkMid, width));
            }
            timer = WagerUi.text(Component.empty(), T.inkDim);
            col.item(timer);
        } else {
            col.item(WagerUi.wrapped(Component.translatable("duels_ld.wager.screen.listing_info", data.listingMinutes()), T.inkMid, width));
        }
        col.item(WagerUi.notice(T, Component.translatable("duels_ld.wager.screen.rules", data.fightMinutes()), T.accent, T.inkMid, width));
        col.item(WagerUi.notice(T, Component.translatable("duels_ld.wager.screen.honesty"), T.gold, T.inkMid, width));
        return col;
    }

    private Flex inventory() {
        Flex inv = Flex.column().gap(2).padding(Insets.of(4, 10, 4, 10)).alignItems(Align.CENTER);
        inv.sizing(Sizing.fill(), Sizing.content());
        int base = menu.stakeSlots();
        for (int row = 0; row < 3; row++) {
            Flex line = Flex.row().gap(2);
            for (int col = 0; col < 9; col++) {
                line.item(new SlotWidget(base + row * 9 + col, true));
            }
            inv.item(line);
        }
        inv.item(WagerUi.spacer(2));
        Flex hotbar = Flex.row().gap(2);
        for (int col = 0; col < 9; col++) {
            hotbar.item(new SlotWidget(base + 27 + col, true));
        }
        inv.item(hotbar);
        return inv;
    }

    private Flex footer() {
        Flex footer = Flex.row().gap(6).padding(Insets.of(5, 10, 5, 10)).alignItems(Align.CENTER);
        footer.sizing(Sizing.fill(), Sizing.fixed(28));
        footer.background(T.bar());
        statusLabel = Label.of(100, status);
        statusLabel.sizing(Sizing.expand(), Sizing.content());
        footer.item(statusLabel);
        Component label = Component.translatable(data.offer()
            ? (data.authorItems().isEmpty() && data.fixedCoins() > 0 ? "duels_ld.wager.screen.accept_challenge" : "duels_ld.wager.screen.offer")
            : data.targetName().isEmpty() ? "duels_ld.wager.screen.post" : "duels_ld.wager.screen.send_challenge");
        submit = WagerUi.button(T, label, WagerUi.labelWidth(label), 18, T.accentDark, T.accent, T.accent, this::confirm);
        footer.item(submit);
        return footer;
    }

    // ── State ────────────────────────────────────────────────────────────────

    /** Why the stake can't be sent yet (shown in the footer), or null when it can. */
    private Component problem() {
        long need = data.fixedCoins() > 0 ? data.fixedCoins() : coins;
        if (need > data.balance()) {
            return Component.translatable("duels_ld.wager.screen.need_coins", need, data.balance());
        }
        int stacks = 0;
        for (ItemStack stack : menu.items()) {
            if (!stack.isEmpty()) {
                stacks++;
            }
        }
        boolean enoughItems = stacks > 0 && stacks >= data.minItems();
        boolean enoughCoins = need > 0 && need >= data.minCoins();
        if (!enoughItems && !enoughCoins) {
            return Component.translatable("duels_ld.wager.screen.too_small", data.minItems(), data.minCoins());
        }
        return null;
    }

    private void refreshState() {
        if (balanceLine != null) {
            long need = data.fixedCoins() > 0 ? data.fixedCoins() : coins;
            balanceLine.text(Component.translatable("duels_ld.wager.screen.balance", data.balance()))
                .color(need > data.balance() ? T.danger : T.inkDim);
        }
        if (submit == null || statusLabel == null) {
            return;
        }
        Component problem = problem();
        boolean ready = problem == null && !waiting && !menu.locked();
        submit.enabled(ready);
        Component shown = waiting ? Component.translatable("duels_ld.wager.screen.placing")
            : statusError ? status : problem != null ? problem : Component.empty();
        if (!shown.equals(statusLabel.text())) {
            statusLabel.text(shown);
        }
        statusLabel.color(statusError || problem != null ? T.danger : T.inkMid);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (T.stale()) {
            T = UiTheme.current();
            canvas.theme(T.theme());
            build();
        }
        if (timer != null) {
            long left = data.remainingMillis() - (System.currentTimeMillis() - openedAt);
            timer.text(Component.translatable("duels_ld.wager.screen.closes_in", WagerUi.time(left)));
        }
        refreshState();
    }

    /** The server's answer to "post"/"offer" (only errors arrive: success closes the screen). */
    public void showStatus(Component message) {
        waiting = false;
        statusError = true;
        status = message;
        refreshState();
    }

    // ── Confirm and send ─────────────────────────────────────────────────────

    private void confirm() {
        if (problem() != null || waiting) {
            return;
        }
        statusError = false;
        long sendCoins = data.fixedCoins() > 0 ? data.fixedCoins() : coins;
        List<ItemStack> items = menu.items();
        Component stake = WagerUi.stakeSummary(sendCoins, items);
        if (data.offer() && data.skipLossWarning()) {
            send(sendCoins);
            return;
        }
        Flex box = Flex.column().gap(6).padding(Insets.of(10));
        box.sizing(Sizing.fixed(280), Sizing.content());
        box.background(T.shell());
        box.item(WagerUi.text(Component.translatable(data.offer()
            ? "duels_ld.wager.confirm.offer_title" : "duels_ld.wager.confirm.create_title"), T.ink));
        box.item(WagerUi.wrapped(Component.translatable("duels_ld.wager.confirm.you_stake", stake), T.inkMid, 260));
        if (data.offer()) {
            Component theirs = WagerUi.stakeSummary(data.authorCoins(), data.authorItems());
            box.item(WagerUi.wrapped(Component.translatable("duels_ld.wager.confirm.winner_gets", stake, theirs), T.inkMid, 260));
            box.item(WagerUi.wrapped(Component.translatable("duels_ld.wager.confirm.loss"), T.danger, 260));
        } else {
            box.item(WagerUi.wrapped(Component.translatable("duels_ld.wager.confirm.create_bank", data.listingMinutes()), T.inkMid, 260));
        }
        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fill(), Sizing.content());
        Modal[] modal = new Modal[1];
        Component yes = Component.translatable(data.offer() ? "duels_ld.wager.confirm.yes_offer" : "duels_ld.wager.confirm.yes_post");
        buttons.item(WagerUi.button(T, Component.translatable("duels_ld.wager.confirm.back"), 60, 18, T.panel, T.accent, T.hairline,
            () -> modal[0].close()));
        buttons.item(WagerUi.button(T, yes, WagerUi.labelWidth(yes), 18, T.accentDark, T.accent, T.accent, () -> {
            modal[0].close();
            send(sendCoins);
        }));
        box.item(buttons);
        modal[0] = Modal.open(canvas, box);
    }

    private void send(long sendCoins) {
        waiting = true;
        refreshState();
        ClientPlayNetworking.send(new WagerSubmitPayload(menu.containerId, sendCoins, comment));
    }

    // ── Slots ────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 400);
            graphics.renderItem(carried, mouseX - 8, mouseY - 8);
            graphics.renderItemDecorations(font, carried, mouseX - 8, mouseY - 8);
            graphics.pose().popPose();
        }
    }

    /**
     * One menu slot drawn on the canvas. Clicks go through the normal container path (shift-click
     * moves between the stake and the inventory); inventory items that can't be staked show red,
     * with the reason under their tooltip.
     */
    private final class SlotWidget extends Widget {
        private final int slotIndex;
        private final boolean inventorySlot;
        private final ItemStackNode item = ItemStackNode.of(ItemStack.EMPTY, 1, 1).stackTooltip(false);

        SlotWidget(int slotIndex, boolean inventorySlot) {
            super(WagerUi.SLOT, WagerUi.SLOT);
            this.slotIndex = slotIndex;
            this.inventorySlot = inventorySlot;
            tooltip(this::lines);
        }

        private ItemStack stack() {
            return menu.getSlot(slotIndex).getItem();
        }

        private boolean refused() {
            ItemStack stack = stack();
            return inventorySlot && !stack.isEmpty() && !menu.allowed(stack);
        }

        private List<Component> lines() {
            ItemStack stack = stack();
            if (stack.isEmpty() || minecraft == null) {
                return null;
            }
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, stack));
            if (inventorySlot && minecraft.player != null) {
                WagerItems.Refusal refusal = WagerItems.check(stack, minecraft.player.registryAccess(), data.bannedItems());
                if (refusal != null) {
                    lines.add(Component.translatable(refusal == WagerItems.Refusal.CONTAINER
                        ? "duels_ld.wager.result.container_item" : "duels_ld.wager.result.banned_item").withStyle(ChatFormatting.RED));
                }
            }
            return lines;
        }

        @Override
        protected void rebuild() {
            add(WagerUi.slotSkin(T, isHovered(), refused()).apply(bounds()));
            add(item.stack(stack()));
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (minecraft != null && minecraft.gameMode != null && minecraft.player != null && !waiting) {
                ClickType type = Screen.hasShiftDown() ? ClickType.QUICK_MOVE : ClickType.PICKUP;
                minecraft.gameMode.handleInventoryMouseClick(menu.containerId, slotIndex, button, type, minecraft.player);
                refresh();
            }
            return true;
        }

        @Override
        protected void draw(CanvasRenderContext ctx) {
            // The menu syncs stacks outside our control; keep the node current every frame.
            ItemStack now = stack();
            if (item.stack() != now) {
                refresh();
            }
            super.draw(ctx);
        }
    }
}
