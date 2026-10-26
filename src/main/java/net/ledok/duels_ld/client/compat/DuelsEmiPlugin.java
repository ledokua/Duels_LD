package net.ledok.duels_ld.client.compat;

import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.widget.Bounds;
import net.ledok.duels_ld.client.WagerStakeScreen;

/**
 * Keeps EMI off the stake screen (player-facing: no EMI there, by house rule). EMI hooks every
 * container screen for input even when it isn't drawn; without bounds it lays its panels out
 * around a small default box, hidden under the full-size canvas, and its favorites then took
 * clicks meant for the stake slots. Claiming the whole screen leaves EMI no room to show or
 * handle anything. Loaded only by EMI (the "emi" entrypoint).
 */
public class DuelsEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        registry.addScreenBoundsProvider(WagerStakeScreen.class, screen -> new Bounds(0, 0, screen.width, screen.height));
    }
}
