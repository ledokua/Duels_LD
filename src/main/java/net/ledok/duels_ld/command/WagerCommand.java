package net.ledok.duels_ld.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.ledok.duels_ld.wager.StakeSource;
import net.ledok.duels_ld.wager.WagerBank;
import net.ledok.duels_ld.wager.WagerConfig;
import net.ledok.duels_ld.wager.WagerManager;
import net.ledok.duels_ld.api.WagerResult;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Wager-duel commands, merged into {@code /duel}. Players: cancel, ready, decline, surrender,
 * accept/reject (the chat buttons), list. Staff: {@code /duel wager list|log|cancel} and the
 * {@code test} subcommands, which stake the held item plus coins until the stake screens exist.
 */
public final class WagerCommand {
    private WagerCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("duel")
            .executes(c -> report(c, net.ledok.duels_ld.wager.WagerScreens.openCreate(c.getSource().getPlayerOrException(), null)))
            .then(Commands.argument("player", EntityArgument.player())
                .executes(c -> report(c, net.ledok.duels_ld.wager.WagerScreens.openCreate(c.getSource().getPlayerOrException(),
                    EntityArgument.getPlayer(c, "player").getUUID()))))
            .then(Commands.literal("terms")
                .then(Commands.argument("number", IntegerArgumentType.integer(1))
                    .executes(c -> report(c, net.ledok.duels_ld.wager.WagerScreens.openTerms(c.getSource().getPlayerOrException(),
                        IntegerArgumentType.getInteger(c, "number"))))))
            .then(Commands.literal("review")
                .then(Commands.argument("offer", IntegerArgumentType.integer(1))
                    .executes(c -> report(c, net.ledok.duels_ld.wager.WagerScreens.openReview(c.getSource().getPlayerOrException(),
                        IntegerArgumentType.getInteger(c, "offer"))))))
            .then(Commands.literal("cancel").executes(WagerCommand::cancel))
            .then(Commands.literal("ready").executes(c -> report(c, WagerManager.ready(c.getSource().getPlayerOrException()))))
            .then(Commands.literal("decline")
                .executes(c -> report(c, WagerManager.declineReady(c.getSource().getPlayerOrException())))
                .then(Commands.argument("number", IntegerArgumentType.integer(1))
                    .executes(c -> report(c, WagerManager.declineChallenge(c.getSource().getPlayerOrException(),
                        IntegerArgumentType.getInteger(c, "number"))))))
            .then(Commands.literal("surrender").executes(WagerCommand::surrender))
            .then(Commands.literal("accept")
                .then(Commands.argument("offer", IntegerArgumentType.integer(1))
                    .executes(c -> report(c, WagerManager.acceptOffer(c.getSource().getPlayerOrException(),
                        IntegerArgumentType.getInteger(c, "offer"))))))
            .then(Commands.literal("reject")
                .then(Commands.argument("offer", IntegerArgumentType.integer(1))
                    .executes(c -> report(c, WagerManager.declineOffer(c.getSource().getPlayerOrException(),
                        IntegerArgumentType.getInteger(c, "offer"))))))
            .then(Commands.literal("withdraw")
                .then(Commands.argument("offer", IntegerArgumentType.integer(1))
                    .executes(c -> report(c, WagerManager.withdrawOffer(c.getSource().getPlayerOrException(),
                        IntegerArgumentType.getInteger(c, "offer"))))))
            .then(Commands.literal("list").executes(WagerCommand::list))
            .then(Commands.literal("report")
                .then(Commands.argument("number", IntegerArgumentType.integer(1))
                    .then(Commands.argument("text", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(c -> report(c, WagerManager.report(c.getSource().getPlayerOrException(),
                            IntegerArgumentType.getInteger(c, "number"),
                            com.mojang.brigadier.arguments.StringArgumentType.getString(c, "text")))))))
            .then(Commands.literal("wager")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list").executes(WagerCommand::staffList))
                .then(Commands.literal("log")
                    .then(Commands.argument("number", IntegerArgumentType.integer(1)).executes(WagerCommand::staffLog)))
                .then(Commands.literal("cancel")
                    .then(Commands.argument("number", IntegerArgumentType.integer(1))
                        .executes(c -> report(c, WagerManager.adminCancel(IntegerArgumentType.getInteger(c, "number"))))))
                .then(Commands.literal("test")
                    .then(Commands.literal("create")
                        .then(Commands.argument("coins", LongArgumentType.longArg(0))
                            .executes(c -> report(c, WagerManager.createListing(c.getSource().getPlayerOrException(),
                                hand(c.getSource().getPlayerOrException()), LongArgumentType.getLong(c, "coins"), "", null)))))
                    .then(Commands.literal("challenge")
                        .then(Commands.argument("target", EntityArgument.player())
                            .then(Commands.argument("coins", LongArgumentType.longArg(0))
                                .executes(c -> report(c, WagerManager.createListing(c.getSource().getPlayerOrException(),
                                    hand(c.getSource().getPlayerOrException()), LongArgumentType.getLong(c, "coins"), "",
                                    EntityArgument.getPlayer(c, "target")))))))
                    .then(Commands.literal("offer")
                        .then(Commands.argument("number", IntegerArgumentType.integer(1))
                            .then(Commands.argument("coins", LongArgumentType.longArg(0))
                                .executes(c -> report(c, WagerManager.makeOffer(c.getSource().getPlayerOrException(),
                                    IntegerArgumentType.getInteger(c, "number"), hand(c.getSource().getPlayerOrException()),
                                    LongArgumentType.getLong(c, "coins"))))))))));
    }

    /** Stakes the main-hand stack (if any): staff testing until the stake screens exist. */
    private static StakeSource hand(ServerPlayer player) {
        return new StakeSource() {
            @Override
            public List<ItemStack> items() {
                ItemStack held = player.getMainHandItem();
                return held.isEmpty() ? List.of() : List.of(held.copy());
            }

            @Override
            public void clear() {
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        };
    }

    private static int report(CommandContext<CommandSourceStack> c, WagerResult result) {
        if (result == WagerResult.OK) {
            return 1;
        }
        if (result == WagerResult.PENDING) {
            c.getSource().sendSuccess(() -> result.message().copy().withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        c.getSource().sendFailure(result.message());
        return 0;
    }

    /** Cancels my listing; else leaves the arena queue; else withdraws my pending offers. */
    private static int cancel(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        if (WagerManager.inArenaQueue(player.getUUID())) {
            return report(c, WagerManager.leaveArenaQueue(player));
        }
        if (WagerManager.openListingOf(player.getUUID()) != null) {
            return report(c, WagerManager.cancelListing(player));
        }
        List<WagerBank.Offer> offers = WagerManager.openOffersBy(player.getUUID());
        if (offers.isEmpty()) {
            return report(c, WagerResult.NO_SUCH_LISTING);
        }
        WagerResult last = WagerResult.OK;
        for (WagerBank.Offer offer : offers) {
            last = WagerManager.withdrawOffer(player, offer.id);
        }
        return report(c, last);
    }

    private static int surrender(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        if (!net.ledok.duels_ld.manager.DuelManager.surrender(player)) {
            return report(c, WagerResult.NOT_IN_WAGER_FIGHT);
        }
        return 1;
    }

    /** Chat list of what a player can answer, until Eden's hub page is there. */
    private static int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        WagerBank bank = WagerManager.bank();
        if (!WagerManager.available() || bank == null) {
            return report(c, WagerResult.DISABLED);
        }
        long now = System.currentTimeMillis();
        int shown = 0;
        c.getSource().sendSuccess(() -> Component.translatable("duels_ld.wager.list.header"), false);
        for (WagerBank.Listing l : bank.listings.values()) {
            if (l.status != WagerBank.ListingStatus.ACTIVE
                || (l.target != null && !l.target.equals(player.getUUID()) && !l.author.equals(player.getUUID()))) {
                continue;
            }
            long left = Math.max(0, l.expiresAt - now) / 1000;
            int offers = WagerManager.openOffersOn(l.number).size();
            net.minecraft.network.chat.MutableComponent line = Component.translatable("duels_ld.wager.list.line", l.number, l.authorName,
                WagerManager.stakeText(l.stake), String.format("%d:%02d", left / 60, left % 60), offers,
                WagerConfig.get().maxOffersPerListing);
            if (!l.author.equals(player.getUUID())) {
                line.append(" ").append(Component.literal("[").append(Component.translatable("duels_ld.wager.button.terms")).append("]")
                    .withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                            net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/duel terms " + l.number))));
            }
            c.getSource().sendSuccess(() -> line, false);
            shown++;
        }
        if (shown == 0) {
            c.getSource().sendSuccess(() -> Component.translatable("duels_ld.wager.list.empty").withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int staffList(CommandContext<CommandSourceStack> c) {
        WagerBank bank = WagerManager.bank();
        if (bank == null) {
            return report(c, WagerResult.DISABLED);
        }
        int open = 0;
        for (WagerBank.Listing l : bank.listings.values()) {
            if (!l.open()) {
                continue;
            }
            open++;
            String line = "#" + l.number + " " + l.status + " " + l.authorName
                + (l.target != null ? " → " + l.targetName : "") + ": " + l.stake.describe()
                + " (" + WagerManager.openOffersOn(l.number).size() + " offers)";
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        String summary = open + " open, " + bank.deliveries.size() + " deliveries pending, " + bank.finished.size() + " finished";
        c.getSource().sendSuccess(() -> Component.literal(summary).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int staffLog(CommandContext<CommandSourceStack> c) {
        WagerBank bank = WagerManager.bank();
        int number = IntegerArgumentType.getInteger(c, "number");
        List<String> lines = bank == null ? null : bank.logs.get(number);
        if (lines == null) {
            return report(c, WagerResult.NO_SUCH_DUEL);
        }
        c.getSource().sendSuccess(() -> Component.literal("Duel #" + number + ":").withStyle(ChatFormatting.GOLD), false);
        for (String line : lines) {
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}
