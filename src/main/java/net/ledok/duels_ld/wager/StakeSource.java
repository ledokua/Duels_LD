package net.ledok.duels_ld.wager;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Where a stake's items come from (the stake screen's slots). Items stay in the source until the
 * duel bank has written the stake to disk; then {@link #clear} takes them, so a crash in between
 * can never leave the items both in the bank and with the player.
 */
public interface StakeSource {
    /** The stacks as they are right now (copies are fine). */
    List<ItemStack> items();

    /** Removes exactly the staked items from the source. Called once, after the bank write. */
    void clear();

    /**
     * When the action returned {@code PENDING} (coins being taken), this reports how it ended:
     * {@code OK}, or why nothing was staked. Server thread.
     */
    default void finished(net.ledok.duels_ld.api.WagerResult result) {
    }
}
