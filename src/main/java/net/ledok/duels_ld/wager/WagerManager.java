package net.ledok.duels_ld.wager;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.ledok.busylib.BusyReasons;
import net.ledok.busylib.BusyState;
import net.ledok.duels_ld.DuelsLdMod;
import net.ledok.duels_ld.api.DuelsApi;
import net.ledok.duels_ld.api.WagerResult;
import net.ledok.duels_ld.integration.EconomyBridge;
import net.ledok.duels_ld.integration.EdenBridge;
import net.ledok.duels_ld.manager.ArenaManager;
import net.ledok.duels_ld.manager.DuelManager;
import net.ledok.duels_ld.manager.MatchmakingManager;
import net.ledok.duels_ld.wager.WagerBank.Delivery;
import net.ledok.duels_ld.wager.WagerBank.Finished;
import net.ledok.duels_ld.wager.WagerBank.Listing;
import net.ledok.duels_ld.wager.WagerBank.ListingStatus;
import net.ledok.duels_ld.wager.WagerBank.Offer;
import net.ledok.duels_ld.wager.WagerBank.OfferStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The wager-duel rules: listings, offers, the ready check, the arena wait, the fight hand-off and
 * every refund and payout. All state that holds stakes lives in {@link WagerBank} and is flushed
 * before anything leaves a player; what lives here is only timers and anti-troll counters, which a
 * restart may forget. Server thread only.
 *
 * <p>Invariant: a stake leaves the bank only through a {@link Delivery} (refund or winnings), and
 * every open listing/offer's stake is in the bank. On startup every open listing and offer is
 * refunded (the plan: a restart returns all stakes), and unfinished deliveries resume.
 */
public final class WagerManager {
    private static final long SECOND = 1000L;

    private static MinecraftServer server;
    private static WagerBank bank;

    // ── Transient state (timers and counters only, never stakes) ─────────────
    private static final Map<UUID, Long> offlineSince = new HashMap<>();
    private static final Map<UUID, Deque<Long>> readyFails = new HashMap<>();
    private static final Map<UUID, Long> blockedUntil = new HashMap<>();
    private static final Map<UUID, Long> relistAfter = new HashMap<>();
    private static final Map<String, Long> rechallengeAfter = new HashMap<>();
    private static final Map<UUID, Long> lastHurt = new HashMap<>();
    /** Players whose stake is being funded (coins in flight); they can't start another action. */
    private static final Set<UUID> staking = new HashSet<>();
    private static final Set<Integer> warnedExpiry = new HashSet<>();
    private static final Set<String> deliveryBusy = new HashSet<>();
    private static final Map<String, Long> deliveryRetryAt = new HashMap<>();
    private static final Map<Integer, ReadyCheck> readyChecks = new HashMap<>();
    /** Listings whose fighters are ready and wait for a free arena, in order. */
    private static final List<Integer> arenaQueue = new ArrayList<>();
    private static long lastSecondTick;

    private static final class ReadyCheck {
        final int number;
        final int offerId;
        final long deadline;
        boolean authorReady;
        boolean challengerReady;
        /** Set when both were ready but one was hurt recently: the start waits until this time. */
        long combatWaitUntil;
        final ServerBossEvent bar;

        ReadyCheck(int number, int offerId, long deadline) {
            this.number = number;
            this.offerId = offerId;
            this.deadline = deadline;
            this.bar = new ServerBossEvent(Component.translatable("duels_ld.wager.ready.bar", number,
                WagerConfig.get().readyCheckSeconds), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
        }
    }

    private WagerManager() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            server = srv;
            WagerConfig.load();
            bank = WagerBank.get(srv);
            recover();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(srv -> {
            readyChecks.values().forEach(check -> check.bar.removeAllPlayers());
            readyChecks.clear();
            arenaQueue.clear();
            staking.clear();
            deliveryBusy.clear();
            server = null;
            bank = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(srv -> {
            if (bank != null) {
                tick();
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> onDisconnect(handler.getPlayer()));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> onJoin(handler.getPlayer()));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (entity instanceof ServerPlayer player && taken > 0) {
                lastHurt.put(player.getUUID(), System.currentTimeMillis());
            }
        });
    }

    /** Wager duels need the switch on, Eden_LD (hub, mail tab) and Economy_LD (the mail store). */
    public static boolean available() {
        return WagerConfig.get().enabled && EdenBridge.isAvailable() && EconomyBridge.isAvailable() && bank != null;
    }

    @Nullable
    public static WagerBank bank() {
        return bank;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Listings
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Puts up a listing (public, or a personal challenge when {@code target} is set). Items stay in
     * {@code source} until the bank has the stake on disk. PENDING means coins are being taken and a
     * chat line reports the end result.
     */
    public static WagerResult createListing(ServerPlayer author, StakeSource source, long coins, String comment,
                                            @Nullable ServerPlayer target) {
        WagerResult pre = precheckCreate(author, target);
        if (pre != WagerResult.OK) {
            return pre;
        }
        WagerConfig cfg = WagerConfig.get();
        UUID id = author.getUUID();
        if (comment.length() > cfg.commentMaxLength) {
            return WagerResult.COMMENT_TOO_LONG;
        }
        List<ItemStack> items = source.items();
        WagerResult stakeCheck = checkStake(items, coins);
        if (stakeCheck != WagerResult.OK) {
            return stakeCheck;
        }
        Stake stake = new Stake(items, coins);
        UUID targetId = target != null ? target.getUUID() : null;
        String targetName = target != null ? target.getGameProfile().getName() : "";
        return fund(author, stake, source, () -> {
            Listing l = new Listing();
            l.number = bank.nextNumber++;
            l.author = id;
            l.authorName = author.getGameProfile().getName();
            l.stake = stake;
            l.comment = comment.strip();
            l.createdAt = System.currentTimeMillis();
            l.expiresAt = l.createdAt + cfg.listingMinutes * 60 * SECOND;
            l.target = targetId;
            l.targetName = targetName;
            bank.listings.put(l.number, l);
            bank.log(l.number, (targetId != null ? "Personal challenge by " : "Listing by ") + l.authorName
                + (targetId != null ? " to " + targetName : "") + ", stake: " + stake.describe()
                + (l.comment.isEmpty() ? "" : ", comment: \"" + l.comment + "\""));
            return l.number;
        }, number -> {
            Listing l = bank.listings.get(number);
            author.sendSystemMessage(Component.translatable(targetId != null
                ? "duels_ld.wager.listing.challenge_sent" : "duels_ld.wager.listing.created",
                number, stakeText(l.stake), targetName).withStyle(ChatFormatting.GREEN));
            if (targetId != null) {
                notifyChallenge(l);
            }
            changed(id);
            listingsChanged();
        });
    }

    /** Whether the player may put up a listing now (before any stake is chosen). */
    public static WagerResult precheckCreate(ServerPlayer author, @Nullable ServerPlayer target) {
        WagerResult basic = canAct(author);
        if (basic != WagerResult.OK) {
            return basic;
        }
        UUID id = author.getUUID();
        if (openListingOf(id) != null) {
            return WagerResult.HAS_LISTING;
        }
        if (System.currentTimeMillis() < relistAfter.getOrDefault(id, 0L)) {
            return WagerResult.RELIST_COOLDOWN;
        }
        if (!ArenaManager.hasArenaFor(1)) {
            return WagerResult.NO_ARENA;
        }
        return target != null ? canChallenge(author, target) : WagerResult.OK;
    }

    /** Whether the player may make an offer on this listing now (before any stake is chosen). */
    public static WagerResult precheckOffer(ServerPlayer challenger, int number) {
        WagerResult basic = canAct(challenger);
        if (basic != WagerResult.OK) {
            return basic;
        }
        WagerConfig cfg = WagerConfig.get();
        UUID id = challenger.getUUID();
        Listing l = bank.listings.get(number);
        if (l == null || !l.open()) {
            return WagerResult.NO_SUCH_LISTING;
        }
        if (l.status != ListingStatus.ACTIVE) {
            return WagerResult.LISTING_CLOSED;
        }
        if (l.author.equals(id)) {
            return WagerResult.OWN_LISTING;
        }
        if (l.target != null && !l.target.equals(id)) {
            return WagerResult.NOT_FOR_YOU;
        }
        List<Offer> open = openOffersOn(number);
        for (Offer o : open) {
            if (o.challenger.equals(id)) {
                return WagerResult.ALREADY_OFFERED;
            }
        }
        if (open.size() >= cfg.maxOffersPerListing) {
            return WagerResult.LISTING_FULL;
        }
        if (openOffersBy(id).size() >= cfg.maxActiveOffersPerPlayer) {
            return WagerResult.TOO_MANY_OFFERS;
        }
        return WagerResult.OK;
    }

    /** The author cancels their open listing (before the fight starts): everyone is refunded. */
    public static WagerResult cancelListing(ServerPlayer author) {
        if (bank == null) {
            return WagerResult.DISABLED;
        }
        Listing l = openListingOf(author.getUUID());
        if (l == null) {
            return WagerResult.NO_SUCH_LISTING;
        }
        if (l.status == ListingStatus.FIGHTING) {
            return WagerResult.IN_DUEL;
        }
        closeListing(l, ListingStatus.CANCELLED, "author cancelled", "cancelled");
        relistAfter.put(author.getUUID(), System.currentTimeMillis() + WagerConfig.get().relistCooldownSeconds * SECOND);
        author.sendSystemMessage(Component.translatable("duels_ld.wager.listing.cancelled_self", l.number));
        return WagerResult.OK;
    }

    /** The target of a personal challenge declines it: the author is refunded. */
    public static WagerResult declineChallenge(ServerPlayer target, int number) {
        if (bank == null) {
            return WagerResult.DISABLED;
        }
        Listing l = bank.listings.get(number);
        if (l == null || !l.open()) {
            return WagerResult.NO_SUCH_LISTING;
        }
        if (l.target == null || !l.target.equals(target.getUUID())) {
            return WagerResult.NOT_FOR_YOU;
        }
        if (l.status != ListingStatus.ACTIVE) {
            return WagerResult.LISTING_CLOSED;
        }
        rechallengeAfter.put(l.author + ">" + l.target,
            System.currentTimeMillis() + WagerConfig.get().rechallengeCooldownSeconds * SECOND);
        closeListing(l, ListingStatus.CANCELLED, target.getGameProfile().getName() + " declined the challenge", "challenge_declined");
        target.sendSystemMessage(Component.translatable("duels_ld.wager.challenge.declined_self", l.number, l.authorName));
        return WagerResult.OK;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Offers
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Offers a stake on a listing. When the listing has coins, the challenger's coins must equal
     * them; on an items-only listing coins are optional.
     */
    public static WagerResult makeOffer(ServerPlayer challenger, int number, StakeSource source, long coins) {
        WagerResult pre = precheckOffer(challenger, number);
        if (pre != WagerResult.OK) {
            return pre;
        }
        UUID id = challenger.getUUID();
        Listing l = bank.listings.get(number);
        if (l.stake.coins() > 0 && coins != l.stake.coins()) {
            return WagerResult.COINS_MUST_MATCH;
        }
        List<ItemStack> items = source.items();
        WagerResult stakeCheck = checkStake(items, coins);
        if (stakeCheck != WagerResult.OK) {
            return stakeCheck;
        }
        Stake stake = new Stake(items, coins);
        return fund(challenger, stake, source, () -> {
            Listing now = bank.listings.get(number);
            if (now == null || now.status != ListingStatus.ACTIVE) {
                return -1;
            }
            Offer o = new Offer();
            o.id = bank.nextOffer++;
            o.number = number;
            o.challenger = id;
            o.challengerName = challenger.getGameProfile().getName();
            o.stake = stake;
            o.createdAt = System.currentTimeMillis();
            bank.offers.put(o.id, o);
            bank.log(number, "Offer " + o.id + " by " + o.challengerName + ", stake: " + stake.describe());
            return o.id;
        }, offerId -> {
            Offer o = bank.offers.get(offerId);
            Listing now = bank.listings.get(number);
            challenger.sendSystemMessage(Component.translatable("duels_ld.wager.offer.sent", number, stakeText(o.stake))
                .withStyle(ChatFormatting.GREEN));
            notifyNewOffer(now, o);
            changed(id, now.author);
            listingsChanged();
        });
    }

    /** The challenger takes back a pending offer: the stake comes straight back. */
    public static WagerResult withdrawOffer(ServerPlayer challenger, int offerId) {
        if (bank == null) {
            return WagerResult.DISABLED;
        }
        Offer o = bank.offers.get(offerId);
        if (o == null || !o.challenger.equals(challenger.getUUID())) {
            return WagerResult.NO_SUCH_OFFER;
        }
        if (o.status != OfferStatus.PENDING) {
            return WagerResult.NOT_PENDING;
        }
        o.status = OfferStatus.WITHDRAWN;
        bank.log(o.number, "Offer " + o.id + " withdrawn by " + o.challengerName);
        refund(o.challenger, o.challengerName, o.stake, o.number, "withdrawn");
        bank.flush();
        processDeliveries();
        challenger.sendSystemMessage(Component.translatable("duels_ld.wager.offer.withdrawn_self", o.number));
        Listing l = bank.listings.get(o.number);
        changed(o.challenger, l != null ? l.author : null);
        listingsChanged();
        return WagerResult.OK;
    }

    /** The author declines an offer: the challenger is refunded, the listing stays up. */
    public static WagerResult declineOffer(ServerPlayer author, int offerId) {
        if (bank == null) {
            return WagerResult.DISABLED;
        }
        Offer o = bank.offers.get(offerId);
        Listing l = o == null ? null : bank.listings.get(o.number);
        if (o == null || l == null || !l.author.equals(author.getUUID())) {
            return WagerResult.NO_SUCH_OFFER;
        }
        if (o.status != OfferStatus.PENDING) {
            return WagerResult.NOT_PENDING;
        }
        o.status = OfferStatus.DECLINED;
        bank.log(o.number, "Offer " + o.id + " declined by the author");
        refund(o.challenger, o.challengerName, o.stake, o.number, "declined");
        bank.flush();
        processDeliveries();
        ServerPlayer challenger = online(o.challenger);
        if (challenger != null) {
            challenger.sendSystemMessage(Component.translatable("duels_ld.wager.offer.declined", o.number, l.authorName)
                .withStyle(ChatFormatting.YELLOW));
        }
        author.sendSystemMessage(Component.translatable("duels_ld.wager.offer.declined_self", o.challengerName));
        changed(o.challenger, l.author);
        listingsChanged();
        return WagerResult.OK;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Accept → ready check → arena → fight
    // ═════════════════════════════════════════════════════════════════════════

    /** The author accepts an offer (after the loss warning): the ready check starts. */
    public static WagerResult acceptOffer(ServerPlayer author, int offerId) {
        WagerResult basic = canAct(author);
        if (basic != WagerResult.OK) {
            return basic;
        }
        Offer o = bank.offers.get(offerId);
        Listing l = o == null ? null : bank.listings.get(o.number);
        if (o == null || l == null || !l.author.equals(author.getUUID())) {
            return WagerResult.NO_SUCH_OFFER;
        }
        if (l.status != ListingStatus.ACTIVE) {
            return WagerResult.LISTING_CLOSED;
        }
        if (o.status != OfferStatus.PENDING) {
            return WagerResult.NOT_PENDING;
        }
        if (!ArenaManager.hasArenaFor(1)) {
            return WagerResult.NO_ARENA;
        }
        ServerPlayer challenger = online(o.challenger);
        if (challenger == null) {
            return WagerResult.CHALLENGER_OFFLINE;
        }
        if (isBlocked(o.challenger) || busyElsewhere(challenger) || DuelManager.isInDuel(challenger)) {
            return WagerResult.TARGET_BUSY;
        }
        WagerConfig cfg = WagerConfig.get();
        long now = System.currentTimeMillis();
        l.status = ListingStatus.PREPARING;
        l.pausedRemaining = Math.max(0, l.expiresAt - now);
        l.acceptedOffer = o.id;
        o.status = OfferStatus.READY_CHECK;
        bank.log(l.number, "Author accepted offer " + o.id + " (" + o.challengerName + "), ready check started");
        bank.flush();

        ReadyCheck check = new ReadyCheck(l.number, o.id, now + cfg.readyCheckSeconds * SECOND);
        readyChecks.put(l.number, check);
        for (ServerPlayer p : List.of(author, challenger)) {
            BusyState.setBusy(p.getUUID(), BusyReasons.IN_DUEL);
            check.bar.addPlayer(p);
            String opponent = p == author ? o.challengerName : l.authorName;
            p.sendSystemMessage(Component.translatable("duels_ld.wager.ready.prompt", l.number, opponent,
                    stakeText(l.stake), stakeText(o.stake))
                .append(" ").append(button("duels_ld.wager.button.ready", "/duel ready", ChatFormatting.GREEN))
                .append(" ").append(button("duels_ld.wager.button.decline", "/duel decline", ChatFormatting.RED)));
            p.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 1f, 1.4f);
        }
        changed(author.getUUID(), o.challenger);
        listingsChanged();
        return WagerResult.OK;
    }

    /** Whether the author may review (accept or decline) this offer right now. */
    public static WagerResult precheckReview(ServerPlayer author, int offerId) {
        if (!available()) {
            return WagerResult.DISABLED;
        }
        Offer o = bank.offers.get(offerId);
        Listing l = o == null ? null : bank.listings.get(o.number);
        if (o == null || l == null || !l.author.equals(author.getUUID())) {
            return WagerResult.NO_SUCH_OFFER;
        }
        if (l.status != ListingStatus.ACTIVE) {
            return WagerResult.LISTING_CLOSED;
        }
        if (o.status != OfferStatus.PENDING) {
            return WagerResult.NOT_PENDING;
        }
        return WagerResult.OK;
    }

    /** "Ready" in the ready check. */
    public static WagerResult ready(ServerPlayer player) {
        ReadyCheck check = checkOf(player.getUUID());
        if (check == null) {
            return WagerResult.NO_READY_CHECK;
        }
        Listing l = bank.listings.get(check.number);
        Offer o = bank.offers.get(check.offerId);
        boolean isAuthor = l.author.equals(player.getUUID());
        if (isAuthor) {
            check.authorReady = true;
        } else {
            check.challengerReady = true;
        }
        bank.log(l.number, player.getGameProfile().getName() + " is ready");
        ServerPlayer other = online(isAuthor ? o.challenger : l.author);
        if (other != null) {
            other.sendSystemMessage(Component.translatable("duels_ld.wager.ready.other_ready", player.getGameProfile().getName())
                .withStyle(ChatFormatting.GREEN));
        }
        player.sendSystemMessage(Component.translatable("duels_ld.wager.ready.self_ready").withStyle(ChatFormatting.GREEN));
        return WagerResult.OK;
    }

    /** "Decline" in the ready check. */
    public static WagerResult declineReady(ServerPlayer player) {
        ReadyCheck check = checkOf(player.getUUID());
        if (check == null) {
            return WagerResult.NO_READY_CHECK;
        }
        Listing l = bank.listings.get(check.number);
        boolean isAuthor = l.author.equals(player.getUUID());
        failReadyCheck(check, isAuthor, !isAuthor, "declined");
        return WagerResult.OK;
    }

    /** Leaving the arena wait: the duel is off and both stakes go back, without a penalty. */
    public static WagerResult leaveArenaQueue(ServerPlayer player) {
        for (Integer number : arenaQueue) {
            Listing l = bank.listings.get(number);
            Offer o = l == null ? null : bank.offers.get(l.acceptedOffer);
            if (l != null && o != null && (l.author.equals(player.getUUID()) || o.challenger.equals(player.getUUID()))) {
                arenaQueue.remove(number);
                freeBusy(l.author, o.challenger);
                // closeListing refunds every still-open offer, this one included.
                bank.log(number, player.getGameProfile().getName() + " left the arena queue; stakes returned");
                closeListing(l, ListingStatus.CANCELLED, null, "left_queue");
                return WagerResult.OK;
            }
        }
        return WagerResult.NO_READY_CHECK;
    }

    private static void failReadyCheck(ReadyCheck check, boolean authorFailed, boolean challengerFailed, String why) {
        readyChecks.remove(check.number);
        check.bar.removeAllPlayers();
        Listing l = bank.listings.get(check.number);
        Offer o = bank.offers.get(check.offerId);
        freeBusy(l.author, o.challenger);
        long now = System.currentTimeMillis();
        if (authorFailed) {
            penalize(l.author, now);
        }
        if (challengerFailed) {
            penalize(o.challenger, now);
        }
        String who = authorFailed && challengerFailed ? l.authorName + " and " + o.challengerName
            : authorFailed ? l.authorName : o.challengerName;
        bank.log(l.number, "Ready check failed (" + why + "): " + who);

        // Back to an active listing; its timer goes on from where it paused.
        l.status = ListingStatus.ACTIVE;
        l.expiresAt = now + Math.max(0, l.pausedRemaining);
        l.pausedRemaining = -1;
        l.acceptedOffer = 0;
        if (challengerFailed) {
            o.status = OfferStatus.CLOSED;
            refund(o.challenger, o.challengerName, o.stake, l.number, "ready_failed");
        } else {
            o.status = OfferStatus.PENDING;
        }
        bank.flush();
        processDeliveries();

        Component line = Component.translatable("duels_ld.wager.ready.failed", l.number, who).withStyle(ChatFormatting.YELLOW);
        for (UUID id : List.of(l.author, o.challenger)) {
            ServerPlayer p = online(id);
            if (p != null) {
                p.sendSystemMessage(line);
            }
        }
        changed(l.author, o.challenger);
        listingsChanged();
    }

    private static void bothReady(ReadyCheck check) {
        Listing l = bank.listings.get(check.number);
        Offer o = bank.offers.get(check.offerId);
        ServerPlayer author = online(l.author);
        ServerPlayer challenger = online(o.challenger);
        if (author == null || challenger == null) {
            failReadyCheck(check, author == null, challenger == null, "went offline");
            return;
        }
        long now = System.currentTimeMillis();
        boolean authorBusy = busyElsewhere(author) || DuelManager.isInDuel(author);
        boolean challengerBusy = busyElsewhere(challenger) || DuelManager.isInDuel(challenger);
        if (authorBusy || challengerBusy) {
            failReadyCheck(check, authorBusy, challengerBusy, "busy elsewhere");
            return;
        }
        // Hurt in the last few seconds: wait (up to the same time) for the fight to settle.
        long combatMs = WagerConfig.get().recentCombatSeconds * SECOND;
        boolean hurt = now - lastHurt.getOrDefault(l.author, 0L) < combatMs
            || now - lastHurt.getOrDefault(o.challenger, 0L) < combatMs;
        if (hurt) {
            if (check.combatWaitUntil == 0) {
                check.combatWaitUntil = now + combatMs;
                for (ServerPlayer p : List.of(author, challenger)) {
                    p.sendSystemMessage(Component.translatable("duels_ld.wager.ready.combat_wait").withStyle(ChatFormatting.YELLOW));
                }
                return;
            }
            if (now < check.combatWaitUntil) {
                return;
            }
            // Still fighting something: the check fails, nobody is penalized.
            readyChecks.remove(check.number);
            check.bar.removeAllPlayers();
            freeBusy(l.author, o.challenger);
            l.status = ListingStatus.ACTIVE;
            l.expiresAt = now + Math.max(0, l.pausedRemaining);
            l.pausedRemaining = -1;
            l.acceptedOffer = 0;
            o.status = OfferStatus.PENDING;
            bank.log(l.number, "Ready check failed: a player was still in combat");
            bank.flush();
            for (ServerPlayer p : List.of(author, challenger)) {
                p.sendSystemMessage(Component.translatable("duels_ld.wager.ready.combat_failed").withStyle(ChatFormatting.YELLOW));
            }
            changed(l.author, o.challenger);
            listingsChanged();
            return;
        }
        readyChecks.remove(check.number);
        check.bar.removeAllPlayers();
        MatchmakingManager.leaveAllQueues(l.author);
        MatchmakingManager.leaveAllQueues(o.challenger);
        o.status = OfferStatus.FIGHTING;
        bank.log(l.number, "Both ready; waiting for an arena");
        bank.flush();
        arenaQueue.add(l.number);
        if (!tryStartFight(l.number)) {
            int position = arenaQueue.indexOf(l.number) + 1;
            for (ServerPlayer p : List.of(author, challenger)) {
                p.sendSystemMessage(Component.translatable("duels_ld.wager.arena.waiting", position)
                    .append(" ").append(button("duels_ld.wager.button.leave_queue", "/duel cancel", ChatFormatting.RED)));
            }
        }
    }

    /** Starts the fight when an arena is free. True when it started (or the duel fell through). */
    private static boolean tryStartFight(int number) {
        Listing l = bank.listings.get(number);
        Offer o = l == null ? null : bank.offers.get(l.acceptedOffer);
        if (l == null || o == null) {
            arenaQueue.remove((Integer) number);
            return true;
        }
        ServerPlayer author = online(l.author);
        ServerPlayer challenger = online(o.challenger);
        if (author == null || challenger == null) {
            arenaQueue.remove((Integer) number);
            freeBusy(l.author, o.challenger);
            bank.log(number, "A fighter went offline while waiting for an arena; stakes returned");
            closeListing(l, ListingStatus.CANCELLED, null, "offline_before_fight");
            return true;
        }
        ArenaManager.Arena arena = ArenaManager.pickArena(1);
        if (arena == null) {
            return false;
        }
        List<ArenaManager.SpawnPoint> t1 = ArenaManager.pickSpawns(arena, 1, 1);
        List<ArenaManager.SpawnPoint> t2 = ArenaManager.pickSpawns(arena, 2, 1);
        if (t1.isEmpty() || t2.isEmpty()) {
            return false;
        }
        arenaQueue.remove((Integer) number);
        ArenaManager.markActive(arena.name);

        l.status = ListingStatus.FIGHTING;
        bank.log(number, "Fight started in arena " + arena.name);
        // The other offers go back now, at the start of the fight (not at acceptance).
        for (Offer other : openOffersOn(number)) {
            if (other.id != o.id) {
                other.status = OfferStatus.CLOSED;
                bank.log(number, "Offer " + other.id + " closed: the author is fighting another challenger");
                refund(other.challenger, other.challengerName, other.stake, number, "author_fought");
            }
        }
        bank.flush();
        processDeliveries();
        DuelManager.startWagerDuel(author, challenger, number, WagerConfig.get().fightMinutes * 60, arena, t1.get(0), t2.get(0));
        changed(l.author, o.challenger);
        listingsChanged();
        return true;
    }

    /**
     * The fight is over ({@link DuelManager} calls this from its end path). {@code winner} null =
     * draw. Pays out, writes history, closes the listing.
     */
    public static void onFightEnd(int number, @Nullable UUID winner, long startedAt) {
        if (bank == null) {
            return;
        }
        Listing l = bank.listings.get(number);
        Offer o = l == null ? null : bank.offers.get(l.acceptedOffer);
        if (l == null || o == null || l.status != ListingStatus.FIGHTING) {
            DuelsLdMod.LOGGER.warn("[Wager] Fight #{} ended but the bank has no fight for it.", number);
            return;
        }
        long now = System.currentTimeMillis();
        Finished f = new Finished();
        f.number = number;
        f.startedAt = startedAt;
        f.endedAt = now;
        f.author = l.author;
        f.authorName = l.authorName;
        f.challenger = o.challenger;
        f.challengerName = o.challengerName;
        f.authorStake = l.stake;
        f.challengerStake = o.stake;
        f.winner = winner;
        bank.finished.put(number, f);
        l.status = ListingStatus.FINISHED;

        if (winner == null) {
            o.status = OfferStatus.DRAW;
            stat(l.author, 2);
            stat(o.challenger, 2);
            bank.log(number, "Draw; both stakes returned");
            refund(l.author, l.authorName, l.stake, number, "draw");
            refund(o.challenger, o.challengerName, o.stake, number, "draw");
        } else {
            boolean authorWon = winner.equals(l.author);
            o.status = authorWon ? OfferStatus.LOST : OfferStatus.WON;
            UUID loser = authorWon ? o.challenger : l.author;
            String winnerName = authorWon ? l.authorName : o.challengerName;
            String loserName = authorWon ? o.challengerName : l.authorName;
            stat(winner, 0);
            stat(loser, 1);
            Stake pot = l.stake.plus(o.stake);
            bank.log(number, winnerName + " won against " + loserName + "; winnings: " + pot.describe());
            deliver(winner, winnerName, pot, number, "duels_ld.wager.mail.won.title", "duels_ld.wager.mail.won.body",
                List.of(Integer.toString(number), loserName), "won");
        }
        bank.flush();
        processDeliveries();

        String duration = formatDuration(now - startedAt);
        for (UUID id : List.of(l.author, o.challenger)) {
            ServerPlayer p = online(id);
            if (p == null) {
                continue;
            }
            Component line = winner == null
                ? Component.translatable("duels_ld.wager.result_line.draw", number, duration)
                : winner.equals(id)
                    ? Component.translatable("duels_ld.wager.result_line.won", number, stakeText(l.stake.plus(o.stake)), duration)
                    : Component.translatable("duels_ld.wager.result_line.lost", number, duration);
            p.sendSystemMessage(line.copy().withStyle(winner == null ? ChatFormatting.YELLOW
                : winner.equals(id) ? ChatFormatting.GOLD : ChatFormatting.RED));
        }
        changed(l.author, o.challenger);
        listingsChanged();
    }

    /** Staff cancel a duel: open listings are refunded; a running fight ends as a draw. */
    public static WagerResult adminCancel(int number) {
        if (bank == null) {
            return WagerResult.DISABLED;
        }
        Listing l = bank.listings.get(number);
        if (l == null || !l.open()) {
            return WagerResult.NO_SUCH_DUEL;
        }
        if (l.status == ListingStatus.FIGHTING) {
            DuelManager.endWagerAsDraw(number);
            return WagerResult.OK;
        }
        ReadyCheck check = readyChecks.remove(number);
        if (check != null) {
            check.bar.removeAllPlayers();
        }
        arenaQueue.remove((Integer) number);
        Offer accepted = bank.offers.get(l.acceptedOffer);
        if (accepted != null) {
            freeBusy(l.author, accepted.challenger);
        }
        closeListing(l, ListingStatus.CANCELLED, "cancelled by staff", "staff");
        return WagerResult.OK;
    }

    /**
     * A complaint about a finished duel, sent to Eden's Moderation with the duel's full log. One per
     * player per duel; only its two fighters can file one.
     */
    public static WagerResult report(ServerPlayer reporter, int number, String text) {
        if (!available()) {
            return WagerResult.DISABLED;
        }
        Finished f = bank.finished.get(number);
        UUID id = reporter.getUUID();
        if (f == null || (!f.author.equals(id) && !f.challenger.equals(id))) {
            return WagerResult.NO_SUCH_DUEL;
        }
        if (f.reportedBy.contains(id)) {
            return WagerResult.ALREADY_REPORTED;
        }
        String trimmed = text.strip();
        if (trimmed.length() > WagerConfig.get().reportMaxLength) {
            return WagerResult.REPORT_TOO_LONG;
        }
        List<String> log = bank.logs.getOrDefault(number, List.of());
        long reportId = EdenBridge.report(server, id, "#" + number, trimmed, log);
        if (reportId < 0) {
            return WagerResult.REPORT_FAILED;
        }
        f.reportedBy.add(id);
        bank.log(number, reporter.getGameProfile().getName() + " filed complaint " + reportId);
        bank.flush();
        changed(id);
        return WagerResult.OK;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Funding (coins + items into the bank)
    // ═════════════════════════════════════════════════════════════════════════

    @FunctionalInterface
    private interface BankWrite {
        /** Adds the stake to the bank (not yet flushed); returns its id, or -1 when it no longer applies. */
        int write();
    }

    @FunctionalInterface
    private interface Done {
        void run(int id);
    }

    /**
     * Moves a stake into the bank. Coins are taken first (async); then, back on the server thread,
     * the items are checked again, the bank is written and flushed, and only then are the items
     * taken from the source. Anything that fails after the coins were taken gives them back.
     */
    private static WagerResult fund(ServerPlayer player, Stake stake, StakeSource source, BankWrite write, Done done) {
        UUID id = player.getUUID();
        if (stake.coins() <= 0) {
            return commitStake(player, stake, source, write, done, false);
        }
        staking.add(id);
        String name = player.getGameProfile().getName();
        EconomyBridge.take(id, name, stake.coins()).whenComplete((ok, error) -> server.execute(() -> {
            staking.remove(id);
            if (error != null || !Boolean.TRUE.equals(ok)) {
                WagerResult failure = error != null ? WagerResult.NO_ECONOMY : WagerResult.NOT_ENOUGH_COINS;
                ServerPlayer p = online(id);
                if (p != null) {
                    p.sendSystemMessage(failure.message().copy().withStyle(ChatFormatting.RED));
                }
                source.finished(failure);
                return;
            }
            DuelsLdMod.LOGGER.info("[Wager] Took {} coins from {} for a stake.", stake.coins(), name);
            ServerPlayer p = online(id);
            WagerResult result = p == null ? WagerResult.STAKE_CHANGED : commitStake(p, stake, source, write, done, true);
            if (!result.ok()) {
                // Nothing went into the bank: the coins go straight back.
                EconomyBridge.give(id, name, stake.coins());
                if (p != null) {
                    p.sendSystemMessage(result.message().copy().withStyle(ChatFormatting.RED));
                }
            }
            source.finished(result);
        }));
        return WagerResult.PENDING;
    }

    private static WagerResult commitStake(ServerPlayer player, Stake stake, StakeSource source, BankWrite write,
                                           Done done, boolean coinsTaken) {
        if (!sameItems(stake.items(), source.items())) {
            return WagerResult.STAKE_CHANGED;
        }
        int id = write.write();
        if (id < 0) {
            return WagerResult.LISTING_CLOSED;
        }
        bank.flush();
        source.clear();
        savePlayer(player);
        done.run(id);
        return WagerResult.OK;
    }

    private static boolean sameItems(List<ItemStack> staked, List<ItemStack> now) {
        List<ItemStack> a = new ArrayList<>();
        List<ItemStack> b = new ArrayList<>();
        staked.forEach(s -> { if (!s.isEmpty()) a.add(s); });
        now.forEach(s -> { if (!s.isEmpty()) b.add(s); });
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static WagerResult checkStake(List<ItemStack> items, long coins) {
        WagerConfig cfg = WagerConfig.get();
        int stacks = 0;
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                continue;
            }
            stacks++;
            WagerItems.Refusal refusal = WagerItems.check(stack, server.registryAccess());
            if (refusal == WagerItems.Refusal.BANNED) {
                return WagerResult.BANNED_ITEM;
            }
            if (refusal == WagerItems.Refusal.CONTAINER) {
                return WagerResult.CONTAINER_ITEM;
            }
        }
        if (stacks > cfg.stakeSlots) {
            return WagerResult.TOO_MANY_ITEMS;
        }
        if (coins < 0 || (cfg.maxCoins > 0 && coins > cfg.maxCoins)) {
            return WagerResult.COINS_TOO_HIGH;
        }
        if (coins > 0 && !EconomyBridge.isAvailable()) {
            return WagerResult.NO_ECONOMY;
        }
        boolean enoughItems = stacks > 0 && stacks >= cfg.minItems;
        boolean enoughCoins = coins > 0 && coins >= cfg.minCoins;
        if (!enoughItems && !enoughCoins) {
            return WagerResult.STAKE_TOO_SMALL;
        }
        return WagerResult.OK;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Closing, refunds and deliveries
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Ends a listing that never got its fight: the author and every open offer are refunded.
     * {@code logLine} (if any) is written to the duel log first.
     */
    private static void closeListing(Listing l, ListingStatus status, @Nullable String logLine, String reason) {
        if (logLine != null) {
            bank.log(l.number, "Listing closed: " + logLine);
        }
        l.status = status;
        l.pausedRemaining = -1;
        refund(l.author, l.authorName, l.stake, l.number, reason);
        List<UUID> touched = new ArrayList<>();
        touched.add(l.author);
        for (Offer o : openOffersOn(l.number)) {
            o.status = OfferStatus.CLOSED;
            refund(o.challenger, o.challengerName, o.stake, l.number, reason);
            touched.add(o.challenger);
        }
        bank.flush();
        processDeliveries();
        changed(touched.toArray(UUID[]::new));
        listingsChanged();
    }

    /** Queues a stake to go back to its owner (not flushed here). */
    private static void refund(UUID owner, String name, Stake stake, int number, String reason) {
        deliver(owner, name, stake, number, "duels_ld.wager.mail.refund.title",
            "duels_ld.wager.mail.refund." + reason, List.of(Integer.toString(number)), "refund");
    }

    private static void deliver(UUID recipient, String name, Stake stake, int number, String titleKey, String bodyKey,
                                List<String> args, String kind) {
        Delivery d = new Delivery();
        d.nonce = "#" + number + "/" + kind + "/" + recipient.toString().substring(0, 8) + "/" + bank.deliveries.size()
            + "-" + System.nanoTime();
        d.recipient = recipient;
        d.recipientName = name;
        d.stake = stake;
        d.titleKey = titleKey;
        d.bodyKey = bodyKey;
        d.args = new ArrayList<>(args);
        bank.deliveries.put(d.nonce, d);
        bank.log(number, "Delivery " + d.nonce + " to " + name + ": " + stake.describe());
    }

    /**
     * Moves every delivery on by one step. Online recipients get coins on their balance and items
     * in their inventory; what doesn't fit (and everything for offline players) goes by mail, which
     * also leaves a written record. Each step is written before it's taken.
     */
    private static void processDeliveries() {
        if (bank == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Delivery d : new ArrayList<>(bank.deliveries.values())) {
            if (deliveryBusy.contains(d.nonce) || now < deliveryRetryAt.getOrDefault(d.nonce, 0L)) {
                continue;
            }
            stepDelivery(d);
        }
    }

    private static void stepDelivery(Delivery d) {
        ServerPlayer player = online(d.recipient);
        // 1. Coins: straight to an online player's balance; offline players get them by mail.
        if (d.coinStage == 0) {
            if (d.stake.coins() <= 0) {
                d.coinStage = 2;
            } else if (player == null) {
                d.mailCoins += d.stake.coins();
                d.coinStage = 2;
            } else {
                d.coinStage = 1;
                bank.flush();
                deliveryBusy.add(d.nonce);
                EconomyBridge.give(d.recipient, d.recipientName, d.stake.coins()).whenComplete((v, error) -> server.execute(() -> {
                    deliveryBusy.remove(d.nonce);
                    if (bank == null || !bank.deliveries.containsKey(d.nonce)) {
                        return;
                    }
                    if (error != null) {
                        DuelsLdMod.LOGGER.warn("[Wager] Paying {} coins to {} failed; sending them by mail.",
                            d.stake.coins(), d.recipientName, error);
                        d.mailCoins += d.stake.coins();
                    }
                    d.coinStage = 2;
                    bank.flush();
                }));
                return;
            }
            bank.flush();
        }
        if (d.coinStage == 1) {
            return; // in flight (startup recovery resolves a stuck one)
        }
        // 2. Items: into an online player's inventory, the rest is left for the mail.
        if (!d.itemsSplit) {
            List<ItemStack> rest = new ArrayList<>();
            if (player != null) {
                for (ItemStack stack : d.stake.copyItems()) {
                    player.getInventory().add(stack);
                    if (!stack.isEmpty()) {
                        rest.add(stack);
                    }
                }
            } else {
                rest.addAll(d.stake.copyItems());
            }
            if (player != null) {
                savePlayer(player);
            }
            d.pendingItems = rest;
            d.itemsSplit = true;
            bank.flush();
        }
        // 3. Mail: the rest in chunks (a mail carries at most maxItems stacks). Always at least one
        // mail per delivery, so every refund and win is on record in the Mail tab.
        if (d.mailInFlight) {
            return;
        }
        boolean needsMail = !d.pendingItems.isEmpty() || d.mailCoins > 0 || d.mailsSent == 0;
        if (!needsMail) {
            finishDelivery(d, player);
            return;
        }
        int max = Math.max(1, EconomyBridge.mailMaxItems());
        List<ItemStack> chunk = new ArrayList<>(d.pendingItems.subList(0, Math.min(max, d.pendingItems.size())));
        long coins = d.mailCoins;
        String mailNonce = d.nonce + "/m" + d.mailsSent;
        List<String> args = new ArrayList<>(d.args);
        args.add(mailNonce);
        d.mailInFlight = true;
        bank.flush();
        deliveryBusy.add(d.nonce);
        EconomyBridge.mail(d.recipient, d.titleKey, d.bodyKey, args, chunk, coins).whenComplete((mailId, error) -> server.execute(() -> {
            deliveryBusy.remove(d.nonce);
            if (bank == null || !bank.deliveries.containsKey(d.nonce)) {
                return;
            }
            d.mailInFlight = false;
            if (error != null || mailId == null || mailId < 0) {
                DuelsLdMod.LOGGER.warn("[Wager] Mail {} to {} failed; retrying.", mailNonce, d.recipientName, error);
                deliveryRetryAt.put(d.nonce, System.currentTimeMillis() + 30 * SECOND);
                bank.flush();
                return;
            }
            d.pendingItems.subList(0, chunk.size()).clear();
            d.mailCoins -= coins;
            d.mailsSent++;
            bank.flush();
        }));
    }

    private static void finishDelivery(Delivery d, @Nullable ServerPlayer player) {
        bank.deliveries.remove(d.nonce);
        deliveryRetryAt.remove(d.nonce);
        bank.flush();
        if (player != null) {
            player.sendSystemMessage(Component.translatable(d.titleKey.equals("duels_ld.wager.mail.won.title")
                    ? "duels_ld.wager.delivered.won" : "duels_ld.wager.delivered.refund",
                    d.args.isEmpty() ? "?" : d.args.get(0), stakeText(d.stake))
                .withStyle(ChatFormatting.GRAY));
        }
        changed(d.recipient);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Startup recovery
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * A restart returns every stake still in play: open listings and offers are refunded (by mail,
     * nobody is online yet). Deliveries cut off mid-way resume; a mail that may already have gone
     * out is looked up by its nonce first, so nothing arrives twice.
     */
    private static void recover() {
        boolean dirty = false;
        for (Listing l : bank.listings.values()) {
            if (!l.open()) {
                continue;
            }
            bank.log(l.number, "Server restarted with the duel " + l.status.name().toLowerCase() + "; all stakes returned");
            boolean wasFighting = l.status == ListingStatus.FIGHTING;
            l.status = ListingStatus.CANCELLED;
            refund(l.author, l.authorName, l.stake, l.number, wasFighting ? "restart_fight" : "restart");
            for (Offer o : openOffersOn(l.number)) {
                o.status = OfferStatus.CLOSED;
                refund(o.challenger, o.challengerName, o.stake, l.number, wasFighting ? "restart_fight" : "restart");
            }
            dirty = true;
        }
        for (Delivery d : bank.deliveries.values()) {
            if (d.coinStage == 1) {
                // Paying coins was cut off: it may or may not have happened. Send them by mail
                // (claimable once) rather than lose them; the log keeps the trace for staff.
                DuelsLdMod.LOGGER.warn("[Wager] Delivery {} to {} was paying {} coins at shutdown; sending them by mail.",
                    d.nonce, d.recipientName, d.stake.coins());
                d.mailCoins += d.stake.coins();
                d.coinStage = 2;
                dirty = true;
            }
            if (d.mailInFlight) {
                String mailNonce = d.nonce + "/m" + d.mailsSent;
                deliveryBusy.add(d.nonce);
                EconomyBridge.mailSent(mailNonce).whenComplete((sent, error) -> server.execute(() -> {
                    deliveryBusy.remove(d.nonce);
                    if (bank == null) {
                        return;
                    }
                    if (error != null) {
                        DuelsLdMod.LOGGER.error("[Wager] Can't check mail {}; holding delivery {} for staff.", mailNonce, d.nonce, error);
                        deliveryBusy.add(d.nonce); // never resend blindly
                        return;
                    }
                    if (Boolean.TRUE.equals(sent)) {
                        int max = Math.max(1, EconomyBridge.mailMaxItems());
                        d.pendingItems.subList(0, Math.min(max, d.pendingItems.size())).clear();
                        d.mailCoins = 0;
                        d.mailsSent++;
                    }
                    d.mailInFlight = false;
                    bank.flush();
                }));
            }
        }
        if (dirty) {
            bank.flush();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Tick, join/leave
    // ═════════════════════════════════════════════════════════════════════════

    private static void tick() {
        long now = System.currentTimeMillis();
        for (ReadyCheck check : new ArrayList<>(readyChecks.values())) {
            if (check.authorReady && check.challengerReady) {
                bothReady(check);
                continue;
            }
            long left = check.deadline - now;
            if (left <= 0) {
                failReadyCheck(check, !check.authorReady, !check.challengerReady, "timed out");
                continue;
            }
            check.bar.setProgress(Math.max(0f, left / (float) (WagerConfig.get().readyCheckSeconds * SECOND)));
            check.bar.setName(Component.translatable("duels_ld.wager.ready.bar", check.number, (left + 999) / SECOND));
        }
        if (now - lastSecondTick < SECOND) {
            return;
        }
        lastSecondTick = now;
        for (Integer number : new ArrayList<>(arenaQueue)) {
            if (!tryStartFight(number)) {
                break; // first in line waits; nobody overtakes
            }
        }
        WagerConfig cfg = WagerConfig.get();
        for (Listing l : new ArrayList<>(bank.listings.values())) {
            if (l.status != ListingStatus.ACTIVE) {
                continue;
            }
            long left = l.expiresAt - now;
            if (left <= 0) {
                closeListing(l, ListingStatus.EXPIRED, "expired", l.target != null ? "challenge_expired" : "expired");
                continue;
            }
            if (left <= 5 * 60 * SECOND && warnedExpiry.add(l.number)) {
                ServerPlayer author = online(l.author);
                if (author != null) {
                    author.sendSystemMessage(Component.translatable("duels_ld.wager.listing.expiring", l.number).withStyle(ChatFormatting.YELLOW));
                }
            }
            if (offlineFor(l.author, now) > cfg.waitingOfflineSeconds * SECOND) {
                closeListing(l, ListingStatus.CANCELLED, l.authorName + " was offline too long", "author_offline");
            }
        }
        for (Offer o : new ArrayList<>(bank.offers.values())) {
            if (o.status == OfferStatus.PENDING && offlineFor(o.challenger, now) > cfg.waitingOfflineSeconds * SECOND) {
                o.status = OfferStatus.CLOSED;
                bank.log(o.number, "Offer " + o.id + " closed: " + o.challengerName + " was offline too long");
                refund(o.challenger, o.challengerName, o.stake, o.number, "challenger_offline");
                bank.flush();
                Listing l = bank.listings.get(o.number);
                changed(o.challenger, l != null ? l.author : null);
                listingsChanged();
            }
        }
        processDeliveries();
    }

    private static long offlineFor(UUID player, long now) {
        Long since = offlineSince.get(player);
        return since == null ? 0 : now - since;
    }

    private static void onDisconnect(ServerPlayer player) {
        UUID id = player.getUUID();
        offlineSince.put(id, System.currentTimeMillis());
        staking.remove(id);
        if (bank == null) {
            return;
        }
        // Leaving during the ready check fails it for that player.
        ReadyCheck check = checkOf(id);
        if (check != null) {
            Listing l = bank.listings.get(check.number);
            boolean isAuthor = l.author.equals(id);
            failReadyCheck(check, isAuthor, !isAuthor, "went offline");
        }
        if (server != null && server.getPlayerList().getBans().isBanned(player.getGameProfile())) {
            onBanned(player);
        }
    }

    /** A banned player's listing and offers are cancelled and refunded; a running fight is a draw. */
    private static void onBanned(ServerPlayer player) {
        UUID id = player.getUUID();
        Listing own = openListingOf(id);
        if (own != null) {
            if (own.status == ListingStatus.FIGHTING) {
                DuelManager.endWagerAsDraw(own.number);
            } else {
                adminCancel(own.number);
            }
        }
        for (Offer o : openOffersBy(id)) {
            Listing l = bank.listings.get(o.number);
            if (o.status == OfferStatus.FIGHTING && l != null && l.status == ListingStatus.FIGHTING) {
                DuelManager.endWagerAsDraw(l.number);
            } else if (o.status == OfferStatus.FIGHTING && l != null) {
                adminCancel(l.number);
            } else if (o.status == OfferStatus.PENDING) {
                o.status = OfferStatus.CLOSED;
                bank.log(o.number, "Offer " + o.id + " closed: " + o.challengerName + " was banned");
                refund(o.challenger, o.challengerName, o.stake, o.number, "banned");
                bank.flush();
            }
        }
    }

    private static void onJoin(ServerPlayer player) {
        offlineSince.remove(player.getUUID());
        if (bank == null) {
            return;
        }
        // Deliveries waiting for this player (items into the inventory) go on with the next tick.
        for (Delivery d : bank.deliveries.values()) {
            if (d.recipient.equals(player.getUUID())) {
                deliveryRetryAt.remove(d.nonce);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Notifications
    // ═════════════════════════════════════════════════════════════════════════

    private static void notifyNewOffer(Listing l, Offer o) {
        ServerPlayer author = online(l.author);
        if (author != null) {
            author.sendSystemMessage(Component.translatable("duels_ld.wager.offer.received", l.number, o.challengerName,
                    stakeText(o.stake))
                .append(" ").append(button("duels_ld.wager.button.review", "/duel review " + o.id, ChatFormatting.GREEN)));
            author.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER, 1f, 1.2f);
        }
        notice(l.author, "duels_ld.wager.mail.new_offer.title", "duels_ld.wager.mail.new_offer.body",
            List.of(Integer.toString(l.number), o.challengerName, o.stake.describe()));
    }

    private static void notifyChallenge(Listing l) {
        ServerPlayer target = online(l.target);
        if (target != null) {
            target.sendSystemMessage(Component.translatable("duels_ld.wager.challenge.received", l.authorName, l.number,
                    stakeText(l.stake))
                .append(" ").append(button("duels_ld.wager.button.terms", "/duel terms " + l.number, ChatFormatting.GREEN))
                .append(" ").append(button("duels_ld.wager.button.decline", "/duel decline " + l.number, ChatFormatting.RED)));
            target.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER, 1f, 1.2f);
        }
        notice(l.target, "duels_ld.wager.mail.challenge.title", "duels_ld.wager.mail.challenge.body",
            List.of(Integer.toString(l.number), l.authorName, l.stake.describe()));
    }

    /** A mail with no attachments, for the record (fire and forget). */
    private static void notice(UUID recipient, String titleKey, String bodyKey, List<String> args) {
        EconomyBridge.mail(recipient, titleKey, bodyKey, args, List.of(), 0).exceptionally(error -> {
            DuelsLdMod.LOGGER.warn("[Wager] Notice mail {} failed.", titleKey, error);
            return -1L;
        });
    }

    private static Component button(String key, String command, ChatFormatting color) {
        return Component.literal("[").append(Component.translatable(key)).append("]")
            .withStyle(Style.EMPTY.withColor(color).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    /** "500 coins + 3 items" with every item on hover. */
    public static Component stakeText(Stake stake) {
        MutableComponent hover = Component.empty();
        boolean first = true;
        for (ItemStack stack : stake.items()) {
            if (!first) {
                hover.append("\n");
            }
            first = false;
            hover.append(Component.literal(stack.getCount() + "× ")).append(stack.getHoverName());
        }
        String one = stake.items().size() == 1 ? ".one" : "";
        Component text = stake.coins() > 0 && stake.hasItems()
            ? Component.translatable("duels_ld.wager.stake.both" + one, stake.coins(), stake.items().size())
            : stake.coins() > 0 ? Component.translatable("duels_ld.wager.stake.coins", stake.coins())
            : Component.translatable("duels_ld.wager.stake.items" + one, stake.items().size());
        return stake.hasItems()
            ? text.copy().withStyle(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)).withUnderlined(true))
            : text;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Queries and helpers
    // ═════════════════════════════════════════════════════════════════════════

    @Nullable
    public static Listing openListingOf(UUID author) {
        if (bank == null) {
            return null;
        }
        for (Listing l : bank.listings.values()) {
            if (l.open() && l.author.equals(author)) {
                return l;
            }
        }
        return null;
    }

    public static List<Offer> openOffersOn(int number) {
        List<Offer> out = new ArrayList<>();
        if (bank != null) {
            for (Offer o : bank.offers.values()) {
                if (o.number == number && o.open()) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    public static List<Offer> openOffersBy(UUID challenger) {
        List<Offer> out = new ArrayList<>();
        if (bank != null) {
            for (Offer o : bank.offers.values()) {
                if (o.challenger.equals(challenger) && o.open()) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    /** Whether the player has a wager duel waiting for an arena (for {@code /duel cancel}). */
    public static boolean inArenaQueue(UUID player) {
        if (bank == null) {
            return false;
        }
        for (Integer number : arenaQueue) {
            Listing l = bank.listings.get(number);
            Offer o = l == null ? null : bank.offers.get(l.acceptedOffer);
            if (l != null && o != null && (l.author.equals(player) || o.challenger.equals(player))) {
                return true;
            }
        }
        return false;
    }

    private static WagerResult canAct(ServerPlayer player) {
        if (!available()) {
            return WagerResult.DISABLED;
        }
        UUID id = player.getUUID();
        if (!player.isAlive()) {
            return WagerResult.BUSY;
        }
        if (staking.contains(id)) {
            return WagerResult.STAKING;
        }
        if (isBlocked(id)) {
            return WagerResult.BLOCKED;
        }
        if (DuelManager.isInDuel(player)) {
            return WagerResult.IN_DUEL;
        }
        if (checkOf(id) != null || inArenaQueue(id)) {
            return WagerResult.BUSY;
        }
        return WagerResult.OK;
    }

    private static WagerResult canChallenge(ServerPlayer author, ServerPlayer target) {
        if (author.getUUID().equals(target.getUUID())) {
            return WagerResult.SELF;
        }
        if (DuelManager.isInDuel(target) || BusyState.isBusy(target.getUUID())) {
            return WagerResult.TARGET_BUSY;
        }
        if (System.currentTimeMillis() < rechallengeAfter.getOrDefault(author.getUUID() + ">" + target.getUUID(), 0L)) {
            return WagerResult.RECHALLENGE_COOLDOWN;
        }
        if (!EdenBridge.personalChallengesAllowed(server, target.getUUID())) {
            return WagerResult.CHALLENGES_OFF;
        }
        return WagerResult.OK;
    }

    /** Busy with something other than this mod's own duel lock (dungeon, raid, menu…). */
    private static boolean busyElsewhere(ServerPlayer player) {
        for (String reason : BusyState.getReasons(player.getUUID())) {
            if (!BusyReasons.IN_DUEL.equals(reason)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlocked(UUID player) {
        return System.currentTimeMillis() < blockedUntil.getOrDefault(player, 0L);
    }

    private static void penalize(UUID player, long now) {
        WagerConfig cfg = WagerConfig.get();
        Deque<Long> fails = readyFails.computeIfAbsent(player, k -> new ArrayDeque<>());
        fails.addLast(now);
        long window = cfg.readyFailWindowMinutes * 60 * SECOND;
        while (!fails.isEmpty() && now - fails.peekFirst() > window) {
            fails.pollFirst();
        }
        if (fails.size() >= cfg.readyFailLimit) {
            fails.clear();
            blockedUntil.put(player, now + cfg.readyFailBlockMinutes * 60 * SECOND);
            ServerPlayer p = online(player);
            if (p != null) {
                p.sendSystemMessage(Component.translatable("duels_ld.wager.ready.blocked", cfg.readyFailBlockMinutes)
                    .withStyle(ChatFormatting.RED));
            }
        }
    }

    private static void freeBusy(UUID... players) {
        for (UUID id : players) {
            BusyState.clearBusy(id, BusyReasons.IN_DUEL);
        }
    }

    @Nullable
    private static ReadyCheck checkOf(UUID player) {
        if (bank == null) {
            return null;
        }
        for (ReadyCheck check : readyChecks.values()) {
            Listing l = bank.listings.get(check.number);
            Offer o = bank.offers.get(check.offerId);
            if (l != null && o != null && (l.author.equals(player) || o.challenger.equals(player))) {
                return check;
            }
        }
        return null;
    }

    /** Writes the player's data (inventory) to disk now, next to the bank write it belongs with. */
    private static void savePlayer(ServerPlayer player) {
        ((net.ledok.duels_ld.mixin.PlayerListInvoker) server.getPlayerList()).duels_ld$save(player);
    }

    private static void stat(UUID player, int index) {
        bank.stats.computeIfAbsent(player, k -> new int[3])[index]++;
    }

    @Nullable
    private static ServerPlayer online(@Nullable UUID id) {
        return id == null || server == null ? null : server.getPlayerList().getPlayer(id);
    }

    private static String formatDuration(long ms) {
        long s = Math.max(0, ms / SECOND);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private static void changed(UUID... players) {
        for (UUID id : players) {
            if (id != null) {
                DuelsApi.fireWagerChanged(id);
            }
        }
    }

    private static void listingsChanged() {
        DuelsApi.fireListingsChanged();
    }
}
