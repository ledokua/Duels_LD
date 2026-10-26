package net.ledok.duels_ld.wager;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.ledok.duels_ld.api.WagerResult;
import net.ledok.duels_ld.integration.EconomyBridge;
import net.ledok.duels_ld.integration.EdenBridge;
import net.ledok.duels_ld.network.OpenWagerReviewPayload;
import net.ledok.duels_ld.network.WagerReviewActionPayload;
import net.ledok.duels_ld.network.WagerStatusPayload;
import net.ledok.duels_ld.network.WagerSubmitPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Opens the wager screens (server side) and receives what they send back. The stake screens are
 * real menus ({@link WagerStakeMenu}); the review screen only shows both stakes, so it's a plain
 * payload. Every action is validated again here: the client never decides anything.
 */
public final class WagerScreens {
    private WagerScreens() {
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(WagerSubmitPayload.TYPE, WagerSubmitPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(WagerReviewActionPayload.TYPE, WagerReviewActionPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(WagerStatusPayload.TYPE, WagerStatusPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenWagerReviewPayload.TYPE, OpenWagerReviewPayload.CODEC);
    }

    public static void registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(WagerSubmitPayload.TYPE, (payload, context) ->
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player.containerMenu instanceof WagerStakeMenu menu && menu.containerId == payload.containerId()) {
                    menu.submit(player, payload.coins(), payload.comment());
                }
            }));
        ServerPlayNetworking.registerGlobalReceiver(WagerReviewActionPayload.TYPE, (payload, context) ->
            context.server().execute(() -> review(context.player(), payload)));
    }

    /** Opens the create screen (a personal challenge when {@code target} is set). */
    public static WagerResult openCreate(ServerPlayer player, @Nullable UUID target) {
        ServerPlayer targetPlayer = null;
        if (target != null) {
            targetPlayer = player.server.getPlayerList().getPlayer(target);
            if (targetPlayer == null) {
                return WagerResult.TARGET_OFFLINE;
            }
        }
        WagerResult pre = WagerManager.precheckCreate(player, targetPlayer);
        if (pre != WagerResult.OK) {
            return pre;
        }
        String targetName = targetPlayer == null ? "" : targetPlayer.getGameProfile().getName();
        withBalance(player, balance -> open(player, data(player, WagerStakeMenu.Data.MODE_CREATE, 0, targetName, balance,
            "", List.of(), 0, "", -1, 0), Component.translatable(targetName.isEmpty()
                ? "duels_ld.wager.screen.create.title" : "duels_ld.wager.screen.challenge.title", targetName)));
        return WagerResult.OK;
    }

    /** Opens the offer screen for a listing: the author's stake, the terms, and your stake slots. */
    public static WagerResult openTerms(ServerPlayer player, int number) {
        WagerResult pre = WagerManager.precheckOffer(player, number);
        if (pre != WagerResult.OK) {
            return pre;
        }
        WagerBank.Listing l = WagerManager.bank().listings.get(number);
        long remaining = Math.max(0, l.expiresAt - System.currentTimeMillis());
        withBalance(player, balance -> {
            WagerBank.Listing now = WagerManager.bank() == null ? null : WagerManager.bank().listings.get(number);
            if (now == null || now.status != WagerBank.ListingStatus.ACTIVE) {
                player.sendSystemMessage(WagerResult.LISTING_CLOSED.message());
                return;
            }
            open(player, data(player, WagerStakeMenu.Data.MODE_OFFER, number, "", balance, now.authorName,
                    now.stake.copyItems(), now.stake.coins(), now.comment, now.stake.coins() > 0 ? now.stake.coins() : -1,
                    remaining),
                Component.translatable("duels_ld.wager.screen.offer.title", number, now.authorName));
        });
        return WagerResult.OK;
    }

    /** Opens the author's review of one offer (accept or decline). */
    public static WagerResult openReview(ServerPlayer author, int offerId) {
        WagerResult pre = WagerManager.precheckReview(author, offerId);
        if (pre != WagerResult.OK) {
            return pre;
        }
        WagerBank bank = WagerManager.bank();
        WagerBank.Offer o = bank.offers.get(offerId);
        WagerBank.Listing l = bank.listings.get(o.number);
        if (author.containerMenu != author.inventoryMenu) {
            author.closeContainer();
        }
        ServerPlayNetworking.send(author, new OpenWagerReviewPayload(o.id, l.number, l.stake.copyItems(), l.stake.coins(),
            o.challengerName, o.stake.copyItems(), o.stake.coins(),
            author.server.getPlayerList().getPlayer(o.challenger) != null,
            EdenBridge.skipLossWarning(author.server, author.getUUID()), EdenBridge.canSetSkipLossWarning(),
            WagerConfig.get().fightMinutes));
        return WagerResult.OK;
    }

    private static void review(ServerPlayer author, WagerReviewActionPayload payload) {
        WagerResult result;
        if (payload.accept()) {
            if (payload.dontAskAgain()) {
                EdenBridge.setSkipLossWarning(author.server, author.getUUID(), true);
            }
            result = WagerManager.acceptOffer(author, payload.offerId());
        } else {
            result = WagerManager.declineOffer(author, payload.offerId());
        }
        ServerPlayNetworking.send(author, new WagerStatusPayload(-1, result.ok(), result.message()));
    }

    private static WagerStakeMenu.Data data(ServerPlayer player, int mode, int number, String targetName, long balance,
                                            String authorName, List<net.minecraft.world.item.ItemStack> authorItems,
                                            long authorCoins, String authorComment, long fixedCoins, long remaining) {
        WagerConfig cfg = WagerConfig.get();
        return new WagerStakeMenu.Data(mode, number, targetName, balance, EconomyBridge.isAvailable(), cfg.stakeSlots,
            cfg.minCoins, cfg.minItems, cfg.maxCoins, cfg.listingMinutes, cfg.fightMinutes, cfg.commentMaxLength,
            List.copyOf(cfg.bannedItems), EdenBridge.skipLossWarning(player.server, player.getUUID()),
            authorName, authorItems, authorCoins, authorComment, fixedCoins, remaining);
    }

    private static void open(ServerPlayer player, WagerStakeMenu.Data data, Component title) {
        if (player.isRemoved() || player.server.getPlayerList().getPlayer(player.getUUID()) != player) {
            return;
        }
        player.openMenu(new ExtendedScreenHandlerFactory<WagerStakeMenu.Data>() {
            @Override
            public WagerStakeMenu.Data getScreenOpeningData(ServerPlayer target) {
                return data;
            }

            @Override
            public Component getDisplayName() {
                return title;
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player opener) {
                return new WagerStakeMenu(containerId, inventory, data);
            }
        });
    }

    /** Looks the balance up (async, Economy_LD's database), then continues on the server thread. */
    private static void withBalance(ServerPlayer player, java.util.function.LongConsumer then) {
        MinecraftServer server = player.server;
        if (!EconomyBridge.isAvailable()) {
            then.accept(0);
            return;
        }
        EconomyBridge.balance(player.getUUID(), player.getGameProfile().getName()).whenComplete((balance, error) ->
            server.execute(() -> then.accept(error != null || balance == null ? 0 : balance)));
    }
}
