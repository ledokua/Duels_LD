package net.ledok.duels_ld.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.duels_ld.network.OpenWagerReviewPayload;
import net.ledok.duels_ld.network.WagerReviewActionPayload;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.Toggle;
import net.minecraft.network.chat.Component;

/**
 * The author's review of one offer: both stakes side by side, what the winner gets, whether the
 * challenger is online. Accept (after the loss warning, unless turned off) starts the ready check;
 * Decline returns the challenger's stake. Nothing here moves items.
 */
public class WagerReviewScreen extends DuelsScreen {
    private final OpenWagerReviewPayload data;
    private Component status = Component.empty();
    private boolean waiting;
    private Label statusLabel;

    public WagerReviewScreen(OpenWagerReviewPayload data) {
        super(Component.translatable("duels_ld.wager.review.title", data.number()), UiTheme.current(), 420, 460, 200, 240, null);
        this.data = data;
    }

    @Override
    protected void build(Flex shell) {
        Flex info = Flex.column().gap(3);
        info.item(text(Component.translatable("duels_ld.wager.review.title", data.number()), T.ink));
        info.item(text(Component.translatable(data.challengerOnline() ? "duels_ld.wager.review.online" : "duels_ld.wager.review.offline",
            data.challengerName()), data.challengerOnline() ? T.good : T.danger));
        Flex header = header(info);
        header.item(closeButton());
        shell.item(header);

        Flex body = Flex.column().gap(5).padding(Insets.of(10));
        body.sizing(Sizing.fill(), Sizing.expand());
        body.background(T.content());

        Flex columns = Flex.row().gap(12);
        columns.sizing(Sizing.fill(), Sizing.content());
        columns.item(stakeColumn(Component.translatable("duels_ld.wager.review.your_stake"), data.authorItems(), data.authorCoins()));
        columns.item(stakeColumn(Component.translatable("duels_ld.wager.review.their_stake", data.challengerName()),
            data.challengerItems(), data.challengerCoins()));
        body.item(columns);

        Component pot = WagerUi.stakeSummary(data.authorCoins() + data.challengerCoins(),
            concat(data.authorItems(), data.challengerItems()));
        body.item(text(Component.translatable("duels_ld.wager.review.winner_gets", pot), T.gold));
        body.item(WagerUi.notice(T, Component.translatable("duels_ld.wager.screen.rules", data.fightMinutes()), T.accent, T.inkMid,
            canvas.width() - 20 - 2 * T.shellPadding()));
        shell.item(body);

        Flex footer = Flex.row().gap(6).padding(Insets.of(6)).alignItems(Align.CENTER);
        footer.sizing(Sizing.fill(), Sizing.fixed(30));
        footer.background(T.bar());
        statusLabel = Label.of(100, status).color(T.danger);
        statusLabel.sizing(Sizing.expand(), Sizing.content());
        footer.item(statusLabel);
        Component declineLabel = Component.translatable("duels_ld.wager.review.decline");
        Button decline = button(declineLabel, 18, T.dangerDark, T.danger, T.danger, () -> send(false, false));
        decline.enabled(!waiting);
        footer.item(decline);
        Component acceptLabel = Component.translatable("duels_ld.wager.review.accept");
        Button accept = button(acceptLabel, 18, T.accentDark, T.accent, T.accent, this::confirmAccept);
        accept.enabled(!waiting && data.challengerOnline());
        if (!data.challengerOnline()) {
            accept.tooltip(Component.translatable("duels_ld.wager.review.offline_hint"));
        }
        footer.item(accept);
        shell.item(footer);
    }

    private Flex stakeColumn(Component title, java.util.List<net.minecraft.world.item.ItemStack> items, long coins) {
        Flex col = Flex.column().gap(3);
        col.sizing(Sizing.expand(), Sizing.content());
        col.item(text(title, T.inkDim));
        int perRow = 9;
        for (int start = 0; start < Math.max(1, items.size()); start += perRow) {
            col.item(WagerUi.stakeRow(T, items.subList(Math.min(start, items.size()), Math.min(items.size(), start + perRow)),
                items.isEmpty() ? 0 : Math.min(perRow, items.size() - start)));
        }
        if (items.isEmpty()) {
            col.item(text(Component.literal("—"), T.inkDim));
        }
        if (coins > 0) {
            col.item(text(Component.translatable("duels_ld.wager.stake.coins", coins), T.gold));
        }
        return col;
    }

    private void confirmAccept() {
        if (waiting) {
            return;
        }
        if (data.skipLossWarning()) {
            send(true, false);
            return;
        }
        boolean[] dontAsk = new boolean[1];
        Flex box = Flex.column().gap(6).padding(Insets.of(10));
        box.sizing(Sizing.fixed(270), Sizing.content());
        box.background(T.shell());
        box.item(text(Component.translatable("duels_ld.wager.confirm.accept_title"), T.ink));
        box.item(WagerUi.wrapped(Component.translatable("duels_ld.wager.confirm.loss"), T.danger, 250));
        if (data.canSetSkip()) {
            box.item(new Toggle(250, Component.translatable("duels_ld.wager.confirm.dont_ask"), false, v -> dontAsk[0] = v));
        }
        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fill(), Sizing.content());
        Modal[] modal = new Modal[1];
        buttons.item(button(Component.translatable("duels_ld.wager.confirm.no"), 50, 18, T.panel, T.accent, T.hairline,
            () -> modal[0].close()));
        buttons.item(button(Component.translatable("duels_ld.wager.confirm.yes_fight"), 18, T.accentDark, T.accent, T.accent, () -> {
            modal[0].close();
            send(true, dontAsk[0]);
        }));
        box.item(buttons);
        modal[0] = Modal.open(canvas, box);
    }

    private void send(boolean accept, boolean dontAskAgain) {
        waiting = true;
        status = Component.empty();
        rebuild();
        ClientPlayNetworking.send(new WagerReviewActionPayload(data.offerId(), accept, dontAskAgain));
    }

    /** The server's answer: success closes the screen (chat says the rest), errors stay visible. */
    public void showStatus(boolean ok, Component message) {
        waiting = false;
        if (ok) {
            onClose();
            return;
        }
        status = message;
        rebuild();
    }

    private static java.util.List<net.minecraft.world.item.ItemStack> concat(java.util.List<net.minecraft.world.item.ItemStack> a,
                                                                          java.util.List<net.minecraft.world.item.ItemStack> b) {
        java.util.List<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
