package net.ledok.duels_ld.wager;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.duels_ld.DuelsLdMod;
import net.ledok.economy_ld.manager.EconomyManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class EconomyBridge {
    private EconomyBridge() {
    }

    public static boolean isAvailable() {
        return FabricLoader.getInstance().isModLoaded("economy_ld");
    }

    public static CompletableFuture<Long> getBalance(UUID uuid, String username) {
        return EconomyManager.getInstance().getBalance(uuid, username);
    }

    public static CompletableFuture<Boolean> takeCurrency(UUID uuid, String username, long amount) {
        return EconomyManager.getInstance().take(uuid, username, amount);
    }

    public static CompletableFuture<Void> giveCurrencyDirect(UUID uuid, String username, long amount) {
        return EconomyManager.getInstance().give(uuid, username, amount);
    }

    public static CompletableFuture<Void> deliverCurrency(UUID uuid, long amount, String reason) {
        return EconomyManager.getInstance().deliverCurrency(uuid, amount, reason);
    }

    public static CompletableFuture<Void> deliverItem(UUID uuid, ItemStack stack, int quantity, String reason) {
        return EconomyManager.getInstance().deliverItem(uuid, stack, quantity, reason);
    }

    public static void payCurrencyToWinner(MinecraftServer server, UUID winnerUuid, long pot, int taxPercent) {
        long tax = Math.max(0L, (pot * Math.max(0, Math.min(100, taxPercent))) / 100L);
        long payout = Math.max(0L, pot - tax);
        if (payout <= 0L) {
            return;
        }
        String reason = "DUEL_WIN";
        deliverCurrency(winnerUuid, payout, reason).exceptionally(error -> {
            DuelsLdMod.LOGGER.warn("Failed to deliver duel winnings to {}", winnerUuid, error);
            return null;
        });
    }

    public static void refundCurrency(UUID uuid, long amount, String reason) {
        if (amount <= 0L) {
            return;
        }
        deliverCurrency(uuid, amount, reason).exceptionally(error -> {
            DuelsLdMod.LOGGER.warn("Failed to refund duel wager to {}", uuid, error);
            return null;
        });
    }

    public static void deliverItemSafe(UUID uuid, ItemStack stack, int quantity, String reason) {
        if (stack == null || stack.isEmpty() || quantity <= 0) {
            return;
        }
        deliverItem(uuid, stack, quantity, reason).exceptionally(error -> {
            DuelsLdMod.LOGGER.warn("Failed to deliver item wager to {}", uuid, error);
            return null;
        });
    }
}
