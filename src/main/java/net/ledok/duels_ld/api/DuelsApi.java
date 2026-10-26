package net.ledok.duels_ld.api;

import net.ledok.duels_ld.manager.BattleRewardStore;
import net.ledok.duels_ld.manager.MMRManager;
import net.ledok.duels_ld.manager.MatchHistoryStore;
import net.ledok.duels_ld.manager.MatchmakingConfigManager;
import net.ledok.duels_ld.manager.MatchmakingManager;
import net.ledok.duels_ld.manager.PlayerStats;
import net.ledok.duels_ld.manager.StatsManager;
import net.ledok.duels_ld.integration.EconomyBridge;
import net.ledok.duels_ld.integration.EdenBridge;
import net.ledok.duels_ld.wager.Stake;
import net.ledok.duels_ld.wager.WagerBank;
import net.ledok.duels_ld.wager.WagerManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Read and queue access to Duels_LD for other mods (source-stable; see API.md). Server side only;
 * call on the server thread. Listeners fire on the server thread.
 *
 * <p>Nothing here creates data: reading a player who never played returns empty/defaults and
 * does not put them on a leaderboard.
 */
public final class DuelsApi {
    private DuelsApi() {
    }

    public enum Mode {
        ONE_V_ONE(1), TWO_V_TWO(2);

        private final int teamSize;

        Mode(int teamSize) {
            this.teamSize = teamSize;
        }

        /** Players per team: 1 or 2. */
        public int teamSize() {
            return teamSize;
        }

        public static Mode ofTeamSize(int teamSize) {
            return teamSize >= 2 ? TWO_V_TWO : ONE_V_ONE;
        }
    }

    public enum Result { WIN, LOSS, DRAW }

    /** Results in one mode. Counters exist since 1.4.0; older matches only count toward legacy totals. */
    public record ModeStats(int rating, int wins, int losses, int draws) {
        public int played() {
            return wins + losses + draws;
        }
    }

    /**
     * @param earnedToday currency earned from battles today (UTC day); 0 when rewards are off
     * @param dailyCap    the sum of the win/loss/draw daily caps
     */
    public record Stats(ModeStats oneVsOne, ModeStats twoVsTwo, long earnedToday, long dailyCap, boolean rewardsEnabled) {
        public ModeStats of(Mode mode) {
            return mode == Mode.ONE_V_ONE ? oneVsOne : twoVsTwo;
        }
    }

    /** {@code played} counts matches since 1.4.0, so a pre-1.4.0 player can be ranked with 0. */
    public record LeaderboardEntry(UUID player, String name, int rating, int played) {
    }

    public enum QueueResult {
        QUEUED, ALREADY_QUEUED, BUSY, IN_MATCH, PENDING_MATCH, NOT_PARTY_LEADER,
        PARTY_WRONG_SIZE, PARTY_MEMBER_UNAVAILABLE, DISABLED
    }

    /**
     * @param pending        matched and waiting for a free arena (the mode flags stay set)
     * @param joinedAtMillis earliest join time of the player's active queues, or -1 when not queued
     * @param viaParty       queued for 2v2 as an Eden_LD party
     * @param searchRange    current Elo search range (it widens over time), or 0 when not searching
     */
    public record QueueState(boolean oneVsOne, boolean twoVsTwo, boolean pending, long joinedAtMillis,
                             boolean viaParty, int searchRange) {
        public static final QueueState NONE = new QueueState(false, false, false, -1L, false, 0);

        public boolean queued() {
            return oneVsOne || twoVsTwo;
        }
    }

    /**
     * One finished match from a player's point of view. Names are as they were at the time.
     * {@code ratingBefore == ratingAfter} for unranked matches.
     */
    public record MatchRecord(long endedAtMillis, Mode mode, Result result, int ratingBefore, int ratingAfter,
                              List<String> teammates, List<String> opponents, long durationMillis) {
    }

    public interface Listener {
        /** The player's queue state may have changed (join, leave, match found, dropped, disconnect). */
        default void onQueueChanged(UUID player) {
        }

        default void onMatchStart(Mode mode, List<UUID> players) {
        }

        /**
         * On a draw, {@code winners} and {@code losers} hold team 1 and team 2. Wager fights fire
         * this too (as ONE_V_ONE); they never touch ratings, ranked stats or ranked history.
         */
        default void onMatchEnd(Mode mode, List<UUID> winners, List<UUID> losers, boolean draw) {
        }

        /** Something in this player's wager duels changed (listing, offer, ready check, fight, delivery). */
        default void onWagerChanged(UUID player) {
        }

        /** A public wager listing appeared, disappeared or changed state. */
        default void onListingsChanged() {
        }
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<LeaderboardEntry>[] LEADERBOARDS = newCache();

    // ── Stats & leaderboards ───────────────────────────────────────────────

    /** Empty when the player never finished a match and has no rating. */
    public static Optional<Stats> stats(MinecraftServer server, UUID player) {
        PlayerStats record = StatsManager.find(player);
        if (record == null && !MMRManager.hasRating(player)) {
            return Optional.empty();
        }
        ModeStats one = modeStats(record, player, Mode.ONE_V_ONE);
        ModeStats two = modeStats(record, player, Mode.TWO_V_TWO);
        MatchmakingConfigManager.RewardConfig cfg = MatchmakingConfigManager.getConfig().rewards;
        boolean rewards = cfg != null && cfg.enabled && EconomyBridge.isAvailable();
        long earned = rewards ? BattleRewardStore.get(server).totalEarned(player, BattleRewardStore.today()) : 0L;
        long cap = cfg == null ? 0L : cfg.winDailyCap + cfg.lossDailyCap + cfg.drawDailyCap;
        return Optional.of(new Stats(one, two, earned, cap, rewards));
    }

    /** Ranked players, best first: anyone with a match in that mode (or a pre-1.4.0 rating change). */
    public static List<LeaderboardEntry> leaderboard(MinecraftServer server, Mode mode, int limit) {
        List<LeaderboardEntry> all = sortedLeaderboard(server, mode);
        return List.copyOf(all.subList(0, Math.max(0, Math.min(limit, all.size()))));
    }

    /** 1-based position, or -1 when unranked. */
    public static int rank(MinecraftServer server, Mode mode, UUID player) {
        List<LeaderboardEntry> all = sortedLeaderboard(server, mode);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).player().equals(player)) {
                return i + 1;
            }
        }
        return -1;
    }

    // ── Queue ──────────────────────────────────────────────────────────────

    /** Same rules as the B screen and {@code /duel queue}, but the caller gets the result instead of chat. */
    public static QueueResult joinQueue(ServerPlayer player, Mode mode) {
        return MatchmakingManager.joinQueue(player, mode, false);
    }

    /** Leaves one queue, or every queue (and any pending match) when {@code mode} is null. Silent. */
    public static void leaveQueue(ServerPlayer player, @Nullable Mode mode) {
        MatchmakingManager.leaveQueue(player, mode, false);
    }

    public static QueueState queueState(UUID player) {
        return MatchmakingManager.queueState(player);
    }

    /** Players searching or waiting for an arena in that mode. */
    public static int queued(Mode mode) {
        return mode == Mode.ONE_V_ONE ? MatchmakingManager.getQueued1v1Count() : MatchmakingManager.getQueued2v2Count();
    }

    // ── History ────────────────────────────────────────────────────────────

    /** Newest first; at most {@value MatchHistoryStore#MAX_PER_PLAYER} are kept per player. */
    public static List<MatchRecord> history(MinecraftServer server, UUID player, int limit) {
        return MatchHistoryStore.get(server).history(player, limit);
    }

    // ── Wager duels ────────────────────────────────────────────────────────

    /** Items and Economy_LD coins one player puts at risk. The stacks are copies. */
    public record WagerStake(List<ItemStack> items, long coins) {
    }

    public record WagerStats(int wins, int losses, int draws) {
        public int played() {
            return wins + losses + draws;
        }
    }

    public enum ListingState { ACTIVE, PREPARING, FIGHTING, FINISHED, CANCELLED, EXPIRED }

    public enum OfferState { PENDING, READY_CHECK, FIGHTING, WON, LOST, DRAW, DECLINED, WITHDRAWN, CLOSED }

    /**
     * A wager listing (a personal challenge when {@code target} is set). {@code number} is the
     * duel number (#1042). While PREPARING or FIGHTING the timer is paused: {@code expiresAtMillis}
     * is then -1 and {@code pausedRemainingMillis} holds what's left.
     */
    public record WagerListing(int number, UUID author, String authorName, WagerStats authorStats, WagerStake stake,
                               String comment, long createdAtMillis, long expiresAtMillis, long pausedRemainingMillis,
                               int offers, ListingState state, @Nullable UUID target, String targetName, boolean mine) {
    }

    public record WagerOffer(int id, int number, UUID challenger, String challengerName, WagerStake stake,
                             OfferState state, boolean online, long createdAtMillis) {
    }

    /**
     * A player's own wager business: their listing and the offers on it, the offers they made,
     * personal challenges waiting for their answer, and everything of theirs in the duel bank.
     */
    public record MyWagers(@Nullable WagerListing listing, List<WagerOffer> offersOnMine, List<WagerOffer> myOffers,
                           List<WagerListing> challengesToMe, WagerStake inBank) {
    }

    /** A finished wager duel. {@code winner} null = draw. */
    public record WagerRecord(int number, long startedAtMillis, long endedAtMillis, UUID author, String authorName,
                              UUID challenger, String challengerName, WagerStake authorStake, WagerStake challengerStake,
                              @Nullable UUID winner, boolean reportedByViewer) {
    }

    /** Wager duels are on: config switch, Eden_LD and Economy_LD present, server running. */
    public static boolean wagersEnabled() {
        return WagerManager.available();
    }

    /**
     * Listings the viewer can see, newest first: every public one that is still open (ACTIVE,
     * PREPARING or FIGHTING), plus personal challenges the viewer sent or received.
     */
    public static List<WagerListing> listings(MinecraftServer server, UUID viewer) {
        WagerBank bank = WagerManager.bank();
        List<WagerListing> out = new ArrayList<>();
        if (bank == null) {
            return out;
        }
        for (WagerBank.Listing l : bank.listings.values()) {
            if (!l.open() || (l.target != null && !l.target.equals(viewer) && !l.author.equals(viewer))) {
                continue;
            }
            out.add(listing(server, l, viewer));
        }
        out.sort(Comparator.comparingLong(WagerListing::createdAtMillis).reversed());
        return out;
    }

    public static MyWagers myWagers(MinecraftServer server, UUID player) {
        WagerBank bank = WagerManager.bank();
        if (bank == null) {
            return new MyWagers(null, List.of(), List.of(), List.of(), new WagerStake(List.of(), 0));
        }
        WagerBank.Listing own = WagerManager.openListingOf(player);
        List<ItemStack> items = new ArrayList<>();
        long coins = 0;
        List<WagerOffer> onMine = new ArrayList<>();
        if (own != null) {
            items.addAll(own.stake.copyItems());
            coins += own.stake.coins();
            for (WagerBank.Offer o : WagerManager.openOffersOn(own.number)) {
                onMine.add(offer(server, o));
            }
        }
        List<WagerOffer> mine = new ArrayList<>();
        for (WagerBank.Offer o : WagerManager.openOffersBy(player)) {
            mine.add(offer(server, o));
            items.addAll(o.stake.copyItems());
            coins += o.stake.coins();
        }
        List<WagerListing> challenges = new ArrayList<>();
        for (WagerBank.Listing l : bank.listings.values()) {
            if (l.open() && player.equals(l.target)) {
                challenges.add(listing(server, l, player));
            }
        }
        return new MyWagers(own == null ? null : listing(server, own, player), onMine, mine, challenges,
            new WagerStake(items, coins));
    }

    /** The player's finished wager duels, newest first. */
    public static List<WagerRecord> wagerHistory(MinecraftServer server, UUID player, int limit) {
        WagerBank bank = WagerManager.bank();
        List<WagerRecord> out = new ArrayList<>();
        if (bank == null) {
            return out;
        }
        for (WagerBank.Finished f : bank.finished.values()) {
            if (f.author.equals(player) || f.challenger.equals(player)) {
                out.add(new WagerRecord(f.number, f.startedAt, f.endedAt, f.author, f.authorName, f.challenger,
                    f.challengerName, stake(f.authorStake), stake(f.challengerStake), f.winner, f.reportedBy.contains(player)));
            }
        }
        out.sort(Comparator.comparingLong(WagerRecord::endedAtMillis).reversed());
        return out.subList(0, Math.max(0, Math.min(limit, out.size())));
    }

    public static WagerStats wagerStats(UUID player) {
        WagerBank bank = WagerManager.bank();
        int[] s = bank == null ? new int[3] : bank.statsOf(player);
        return new WagerStats(s[0], s[1], s[2]);
    }

    /** Whether the player turned off the "you'll lose your stake" warning (Eden hub setting). */
    public static boolean skipsLossWarning(MinecraftServer server, UUID player) {
        return EdenBridge.skipLossWarning(server, player);
    }

    /**
     * Opens Duels' create screen (stake slots, coins, comment). {@code target} non-null makes it a
     * personal challenge to that (online) player. On OK the screen replaces whatever was open.
     */
    public static WagerResult openCreate(ServerPlayer player, @Nullable UUID target) {
        return net.ledok.duels_ld.wager.WagerScreens.openCreate(player, target);
    }

    /** Opens the offer screen for listing {@code number}: the author's stake, the terms, your stake. */
    public static WagerResult openTerms(ServerPlayer player, int number) {
        return net.ledok.duels_ld.wager.WagerScreens.openTerms(player, number);
    }

    /** Opens the author's review of an offer: both stakes, Accept (with the loss warning) or Decline. */
    public static WagerResult openReview(ServerPlayer author, int offerId) {
        return net.ledok.duels_ld.wager.WagerScreens.openReview(author, offerId);
    }

    /** The author cancels their listing; everyone is refunded. */
    public static WagerResult cancelListing(ServerPlayer author) {
        return WagerManager.cancelListing(author);
    }

    /** The challenger withdraws a pending offer; the stake comes back. */
    public static WagerResult withdrawOffer(ServerPlayer challenger, int offerId) {
        return WagerManager.withdrawOffer(challenger, offerId);
    }

    /** The author declines an offer; the challenger is refunded, the listing stays. */
    public static WagerResult declineOffer(ServerPlayer author, int offerId) {
        return WagerManager.declineOffer(author, offerId);
    }

    /**
     * The author accepts an offer and the ready check starts. Show the "you'll lose your stake"
     * warning first unless {@link #skipsLossWarning}.
     */
    public static WagerResult acceptOffer(ServerPlayer author, int offerId) {
        return WagerManager.acceptOffer(author, offerId);
    }

    /** The target of a personal challenge declines it; the author is refunded. */
    public static WagerResult declineChallenge(ServerPlayer target, int number) {
        return WagerManager.declineChallenge(target, number);
    }

    /** A fighter files a complaint about a finished duel (to Eden's Moderation, with the duel's log). */
    public static WagerResult report(ServerPlayer reporter, int number, String text) {
        return WagerManager.report(reporter, number, text);
    }

    private static WagerListing listing(MinecraftServer server, WagerBank.Listing l, UUID viewer) {
        boolean paused = l.status != WagerBank.ListingStatus.ACTIVE;
        return new WagerListing(l.number, l.author, l.authorName, wagerStats(l.author), stake(l.stake), l.comment,
            l.createdAt, paused ? -1 : l.expiresAt, paused ? Math.max(0, l.pausedRemaining) : 0,
            WagerManager.openOffersOn(l.number).size(), ListingState.valueOf(l.status.name()), l.target, l.targetName,
            l.author.equals(viewer));
    }

    private static WagerOffer offer(MinecraftServer server, WagerBank.Offer o) {
        return new WagerOffer(o.id, o.number, o.challenger, o.challengerName, stake(o.stake),
            OfferState.valueOf(o.status.name()), server.getPlayerList().getPlayer(o.challenger) != null, o.createdAt);
    }

    private static WagerStake stake(Stake stake) {
        return new WagerStake(stake.copyItems(), stake.coins());
    }

    // ── Listeners ──────────────────────────────────────────────────────────

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    /** Internal — fired by Duels_LD; not API. */
    public static void fireQueueChanged(UUID player) {
        LISTENERS.forEach(listener -> listener.onQueueChanged(player));
    }

    /** Internal — fired by Duels_LD; not API. */
    public static void fireMatchStart(Mode mode, List<UUID> players) {
        List<UUID> copy = List.copyOf(players);
        LISTENERS.forEach(listener -> listener.onMatchStart(mode, copy));
    }

    /** Internal — fired by Duels_LD; not API. */
    public static void fireMatchEnd(Mode mode, List<UUID> winners, List<UUID> losers, boolean draw) {
        invalidateLeaderboards();
        List<UUID> w = List.copyOf(winners);
        List<UUID> l = List.copyOf(losers);
        LISTENERS.forEach(listener -> listener.onMatchEnd(mode, w, l, draw));
    }

    /** Internal — fired by Duels_LD; not API. */
    public static void fireWagerChanged(UUID player) {
        LISTENERS.forEach(listener -> listener.onWagerChanged(player));
    }

    /** Internal — fired by Duels_LD; not API. */
    public static void fireListingsChanged() {
        LISTENERS.forEach(Listener::onListingsChanged);
    }

    /** Internal — drops cached leaderboards (results or ratings changed). */
    public static void invalidateLeaderboards() {
        LEADERBOARDS[0] = null;
        LEADERBOARDS[1] = null;
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private static ModeStats modeStats(@Nullable PlayerStats record, UUID player, Mode mode) {
        int rating = mode == Mode.ONE_V_ONE ? MMRManager.getRating1v1(player) : MMRManager.getRating2v2(player);
        if (record == null) {
            return new ModeStats(rating, 0, 0, 0);
        }
        int size = mode.teamSize();
        return new ModeStats(rating, record.getWins(size), record.getLosses(size), record.getDraws(size));
    }

    private static List<LeaderboardEntry> sortedLeaderboard(MinecraftServer server, Mode mode) {
        int slot = mode.ordinal();
        List<LeaderboardEntry> cached = LEADERBOARDS[slot];
        if (cached != null) {
            return cached;
        }
        Map<UUID, Integer> ratings = mode == Mode.ONE_V_ONE ? MMRManager.getAll1v1Ratings() : MMRManager.getAll2v2Ratings();
        Map<UUID, PlayerStats> stats = StatsManager.all();
        int start = MMRManager.startingRating();
        Set<UUID> candidates = new HashSet<>(ratings.keySet());
        candidates.addAll(stats.keySet());
        List<LeaderboardEntry> out = new ArrayList<>();
        for (UUID id : candidates) {
            PlayerStats record = stats.get(id);
            int played = record == null ? 0 : record.getPlayed(mode.teamSize());
            Integer rating = ratings.get(id);
            // Before 1.4.0, merely looking at a rating stored it at the start value; those entries
            // (and anyone without matches in this mode) stay off the board.
            if (played == 0 && (rating == null || rating == start)) {
                continue;
            }
            out.add(new LeaderboardEntry(id, nameOf(server, id), rating == null ? start : rating, played));
        }
        out.sort(Comparator.comparingInt(LeaderboardEntry::rating).reversed()
            .thenComparing(Comparator.comparingInt(LeaderboardEntry::played).reversed())
            .thenComparing(LeaderboardEntry::name, String.CASE_INSENSITIVE_ORDER));
        List<LeaderboardEntry> result = List.copyOf(out);
        LEADERBOARDS[slot] = result;
        return result;
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return server.getProfileCache().get(id).map(profile -> profile.getName())
            .orElse(id.toString().substring(0, 8));
    }

    @SuppressWarnings("unchecked")
    private static List<LeaderboardEntry>[] newCache() {
        return (List<LeaderboardEntry>[]) new List[2];
    }
}
