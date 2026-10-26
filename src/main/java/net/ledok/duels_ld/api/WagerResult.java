package net.ledok.duels_ld.api;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Why a wager action did or didn't happen. {@link #message} is the player-facing line (lang key
 * {@code duels_ld.wager.result.<name>}); hosts like Eden's hub show it as a toast.
 */
public enum WagerResult {
    OK,
    /** The action was accepted and finishes shortly (coins are being taken); a chat line follows. */
    PENDING,
    DISABLED,
    BUSY,
    IN_DUEL,
    BLOCKED,
    STAKING,
    HAS_LISTING,
    RELIST_COOLDOWN,
    NO_ARENA,
    STAKE_TOO_SMALL,
    TOO_MANY_ITEMS,
    COINS_TOO_HIGH,
    NOT_ENOUGH_COINS,
    NO_ECONOMY,
    BANNED_ITEM,
    CONTAINER_ITEM,
    COMMENT_TOO_LONG,
    STAKE_CHANGED,
    NO_SUCH_LISTING,
    LISTING_CLOSED,
    OWN_LISTING,
    NOT_FOR_YOU,
    LISTING_FULL,
    ALREADY_OFFERED,
    TOO_MANY_OFFERS,
    COINS_MUST_MATCH,
    NO_SUCH_OFFER,
    NOT_YOURS,
    NOT_PENDING,
    CHALLENGER_OFFLINE,
    SELF,
    TARGET_OFFLINE,
    TARGET_BUSY,
    CHALLENGES_OFF,
    RECHALLENGE_COOLDOWN,
    NO_READY_CHECK,
    NOT_IN_WAGER_FIGHT,
    NO_SUCH_DUEL,
    ALREADY_REPORTED,
    REPORT_TOO_LONG,
    REPORT_FAILED;

    public boolean ok() {
        return this == OK || this == PENDING;
    }

    public String key() {
        return "duels_ld.wager.result." + name().toLowerCase(Locale.ROOT);
    }

    public Component message(Object... args) {
        return Component.translatable(key(), args);
    }
}
