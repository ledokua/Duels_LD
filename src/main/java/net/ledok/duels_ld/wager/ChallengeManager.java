package net.ledok.duels_ld.wager;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.ledok.duels_ld.DuelsLdMod;
import net.ledok.duels_ld.manager.ActivityManager;
import net.ledok.duels_ld.manager.DuelManager;
import net.ledok.duels_ld.manager.DuelSettings;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class ChallengeManager {
    private static final long EXPIRY_MS = 60_000L;
    private static final Map<UUID, Deque<PendingChallenge>> pending = new HashMap<>();

    private ChallengeManager() {
    }

    public static void init() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            pending.remove(id);
            for (Deque<PendingChallenge> queue : pending.values()) {
                queue.removeIf(c -> c.challenger.equals(id));
            }
        });
    }

    public static boolean challenge(ServerPlayer challenger, ServerPlayer target, Wager wager) {
        if (challenger.getUUID().equals(target.getUUID())) {
            challenger.sendSystemMessage(Component.translatable("duels_ld.challenge.cannot_self"));
            return false;
        }
        if (ActivityManager.isPlayerBusy(challenger.getUUID())) {
            challenger.sendSystemMessage(Component.translatable("duels_ld.challenge.you_busy"));
            return false;
        }
        if (ActivityManager.isPlayerBusy(target.getUUID())) {
            challenger.sendSystemMessage(Component.translatable("duels_ld.challenge.target_busy", target.getName().getString()));
            return false;
        }
        if (wager.getKind() == Wager.Kind.CURRENCY && !EconomyBridge.isAvailable()) {
            challenger.sendSystemMessage(Component.translatable("duels_ld.challenge.economy_missing"));
            return false;
        }
        if (wager.getKind() == Wager.Kind.ITEM) {
            int available = countMatchingItems(challenger, wager.getItemTemplate());
            if (available < wager.getItemCount()) {
                challenger.sendSystemMessage(Component.translatable("duels_ld.challenge.not_enough_items"));
                return false;
            }
        }
        long expiresAt = System.currentTimeMillis() + EXPIRY_MS;
        PendingChallenge pendingChallenge = new PendingChallenge(challenger.getUUID(), target.getUUID(), wager, expiresAt);
        Deque<PendingChallenge> queue = pending.computeIfAbsent(target.getUUID(), k -> new ArrayDeque<>());
        queue.removeIf(c -> c.challenger.equals(challenger.getUUID()));
        queue.addLast(pendingChallenge);
        challenger.sendSystemMessage(Component.translatable(
                "duels_ld.challenge.sent",
                target.getName().getString(),
                wager.describe()
        ));
        target.sendSystemMessage(Component.translatable(
                "duels_ld.challenge.received",
                challenger.getName().getString(),
                wager.describe(),
                challenger.getName().getString()
        ));
        return true;
    }

    public static boolean accept(ServerPlayer target, ServerPlayer challenger) {
        purgeExpired();
        Deque<PendingChallenge> queue = pending.get(target.getUUID());
        if (queue == null) {
            target.sendSystemMessage(Component.translatable("duels_ld.challenge.no_pending"));
            return false;
        }
        PendingChallenge match = null;
        for (PendingChallenge c : queue) {
            if (c.challenger.equals(challenger.getUUID())) {
                match = c;
                break;
            }
        }
        if (match == null) {
            target.sendSystemMessage(Component.translatable("duels_ld.challenge.no_pending_from", challenger.getName().getString()));
            return false;
        }
        final PendingChallenge accepted = match;
        queue.remove(accepted);
        if (queue.isEmpty()) {
            pending.remove(target.getUUID());
        }

        if (ActivityManager.isPlayerBusy(challenger.getUUID()) || ActivityManager.isPlayerBusy(target.getUUID())) {
            target.sendSystemMessage(Component.translatable("duels_ld.challenge.cancelled_busy"));
            return false;
        }

        if (accepted.wager.getKind() == Wager.Kind.ITEM) {
            int taSide = countMatchingItems(target, accepted.wager.getItemTemplate());
            if (taSide < accepted.wager.getItemCount()) {
                target.sendSystemMessage(Component.translatable("duels_ld.challenge.not_enough_items"));
                return false;
            }
            int chSide = countMatchingItems(challenger, accepted.wager.getItemTemplate());
            if (chSide < accepted.wager.getItemCount()) {
                challenger.sendSystemMessage(Component.translatable("duels_ld.challenge.not_enough_items"));
                target.sendSystemMessage(Component.translatable("duels_ld.challenge.challenger_short"));
                return false;
            }
            int taken = removeMatchingItems(challenger, accepted.wager.getItemTemplate(), accepted.wager.getItemCount())
                    + removeMatchingItems(target, accepted.wager.getItemTemplate(), accepted.wager.getItemCount());
            if (taken < accepted.wager.getItemCount() * 2) {
                DuelsLdMod.LOGGER.warn("Item wager escrow under-taken: {}", taken);
            }
            startEscrowedDuel(challenger, target, accepted.wager);
            return true;
        }

        if (accepted.wager.getKind() == Wager.Kind.CURRENCY) {
            long amount = accepted.wager.getCurrencyAmount();
            EconomyBridge.takeCurrency(challenger.getUUID(), challenger.getName().getString(), amount)
                    .thenCompose(challengerOk -> {
                        if (!Boolean.TRUE.equals(challengerOk)) {
                            return java.util.concurrent.CompletableFuture.completedFuture(EscrowResult.CHALLENGER_INSUFFICIENT);
                        }
                        return EconomyBridge.takeCurrency(target.getUUID(), target.getName().getString(), amount)
                                .thenApply(targetOk -> {
                                    if (!Boolean.TRUE.equals(targetOk)) {
                                        EconomyBridge.giveCurrencyDirect(challenger.getUUID(), challenger.getName().getString(), amount);
                                        return EscrowResult.TARGET_INSUFFICIENT;
                                    }
                                    return EscrowResult.OK;
                                });
                    })
                    .whenComplete((result, err) -> target.server.execute(() -> {
                        if (err != null) {
                            DuelsLdMod.LOGGER.warn("Challenge escrow failed", err);
                            target.sendSystemMessage(Component.translatable("duels_ld.challenge.escrow_failed"));
                            return;
                        }
                        switch (result) {
                            case CHALLENGER_INSUFFICIENT -> {
                                challenger.sendSystemMessage(Component.translatable("duels_ld.challenge.insufficient_funds"));
                                target.sendSystemMessage(Component.translatable("duels_ld.challenge.challenger_short"));
                            }
                            case TARGET_INSUFFICIENT -> target.sendSystemMessage(Component.translatable("duels_ld.challenge.insufficient_funds"));
                            case OK -> startEscrowedDuel(challenger, target, accepted.wager);
                        }
                    }));
            return true;
        }

        startEscrowedDuel(challenger, target, accepted.wager);
        return true;
    }

    private static void startEscrowedDuel(ServerPlayer challenger, ServerPlayer target, Wager wager) {
        DuelSettings settings = new DuelSettings();
        DuelManager.startChallengeDuel(challenger, target, settings, wager);
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Deque<PendingChallenge>>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Deque<PendingChallenge>> entry = it.next();
            entry.getValue().removeIf(c -> c.expiresAt < now);
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
    }

    private static int countMatchingItems(ServerPlayer player, ItemStack template) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (matches(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int removeMatchingItems(ServerPlayer player, ItemStack template, int needed) {
        int remaining = needed;
        for (int i = 0; i < player.getInventory().items.size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (matches(stack, template)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        player.inventoryMenu.sendAllDataToRemote();
        return needed - remaining;
    }

    private static boolean matches(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return ItemStack.isSameItemSameComponents(a, b);
    }

    private enum EscrowResult { OK, CHALLENGER_INSUFFICIENT, TARGET_INSUFFICIENT }

    private static final class PendingChallenge {
        final UUID challenger;
        final UUID target;
        final Wager wager;
        final long expiresAt;

        PendingChallenge(UUID challenger, UUID target, Wager wager, long expiresAt) {
            this.challenger = challenger;
            this.target = target;
            this.wager = wager;
            this.expiresAt = expiresAt;
        }
    }
}
