package net.ledok.duels_ld.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.duels_ld.DuelsLdMod;
import net.ledok.economy_ld.manager.EconomyManager;
import net.ledok.economy_ld.util.CurrencyFormatter;

import java.util.UUID;

/**
 * Thin, optional bridge to Economy_LD. Economy_LD is a soft dependency, so callers must check
 * {@link #isAvailable()} before touching any other method (which references Economy_LD classes and
 * would fail to link if the mod is absent).
 */
public final class EconomyBridge {
    private EconomyBridge() {
    }

    public static boolean isAvailable() {
        return FabricLoader.getInstance().isModLoaded("economy_ld");
    }

    /** Delivers currency to a player (online or offline, via Economy_LD's pending-delivery mailbox). */
    public static void deliver(UUID uuid, long amount, String reason) {
        if (amount <= 0) {
            return;
        }
        EconomyManager.getInstance().deliverCurrency(uuid, amount, reason).exceptionally(error -> {
            DuelsLdMod.LOGGER.warn("Failed to deliver battle reward to {}", uuid, error);
            return null;
        });
    }

    /** Formats an amount with the configured currency symbol (e.g. "100 LC"). */
    public static String format(long amount) {
        return CurrencyFormatter.format(amount);
    }
}
