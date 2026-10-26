package net.ledok.duels_ld.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.duels_ld.DuelsLdMod;
import net.ledok.economy_ld.mail.EconomyMail;
import net.ledok.economy_ld.mail.MailAttachment;
import net.ledok.economy_ld.mail.MailContent;
import net.ledok.economy_ld.mail.MailDraft;
import net.ledok.economy_ld.mail.MailKind;
import net.ledok.economy_ld.manager.EconomyManager;
import net.ledok.economy_ld.util.CurrencyFormatter;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

    // ── Wager duels: coins and mail ───────────────────────────────────────────

    /** The fixed sender of every Duels mail, so startup recovery can find what was already sent. */
    public static final UUID MAIL_SENDER = UUID.nameUUIDFromBytes("duels_ld:mail".getBytes(StandardCharsets.UTF_8));
    /** Eden translates sender names starting with "duels_ld.". */
    public static final String MAIL_SENDER_NAME = "duels_ld.mail.sender";

    /** Takes coins from a balance; completes with false when the balance is too low. Async. */
    public static CompletableFuture<Boolean> take(UUID uuid, String name, long amount) {
        return EconomyManager.getInstance().take(uuid, name, amount);
    }

    /** Adds coins to a balance (online or offline). Async. */
    public static CompletableFuture<Void> give(UUID uuid, String name, long amount) {
        return EconomyManager.getInstance().give(uuid, name, amount);
    }

    public static CompletableFuture<Long> balance(UUID uuid, String name) {
        return EconomyManager.getInstance().getBalance(uuid, name);
    }

    /** Items one mail can carry. */
    public static int mailMaxItems() {
        return EconomyMail.maxItems();
    }

    /**
     * Sends one translated mail with attachments, kept the longest the server allows. Completes
     * with the mail id, or -1 when it reached nobody. Async (the mail store is a database).
     */
    public static CompletableFuture<Long> mail(UUID recipient, String titleKey, String bodyKey, List<String> args,
                                               List<ItemStack> items, long coins) {
        List<MailAttachment> attachments = new ArrayList<>();
        for (ItemStack stack : items) {
            attachments.add(MailAttachment.of(stack));
        }
        MailDraft draft = MailDraft.builder(items.isEmpty() && coins <= 0 ? MailKind.SYSTEM : MailKind.REWARD,
                MailContent.translated(titleKey, bodyKey, args))
            .sender(MAIL_SENDER_NAME, MAIL_SENDER)
            .to(recipient)
            .items(attachments)
            .coins(coins)
            .expiresInDays(EconomyMail.maxExpiryDays())
            .build();
        return EconomyMail.send(draft);
    }

    /** Whether a Duels mail carrying {@code nonce} among its arguments was already sent. Async. */
    public static CompletableFuture<Boolean> mailSent(String nonce) {
        return EconomyMail.sent(MAIL_SENDER, 5000).thenApply(sent ->
            sent.stream().anyMatch(mail -> mail.content().args().contains(nonce)));
    }
}
