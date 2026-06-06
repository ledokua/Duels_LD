package net.ledok.duels_ld.manager;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.duels_ld.integration.EconomyBridge;
import net.ledok.duels_ld.network.RewardStatusPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Pays Economy_LD currency for matchmade battles. Amount = flat base (win/loss/draw) scaled by a
 * 0–1 quality factor (match length), an Elo gate for wins (stomping weak opponents pays less), and
 * same-opponent decay, then capped by the player's remaining daily allowance. All knobs live in
 * {@link MatchmakingConfigManager.RewardConfig}. No-op if rewards are disabled or Economy_LD absent.
 */
public final class BattleRewards {
    private enum Outcome { WIN, LOSS, DRAW }

    private BattleRewards() {
    }

    public static void awardDuel(MinecraftServer server, ActiveDuel duel, boolean draw,
                                 UUID winner, UUID loser, int winnerElo, int loserElo) {
        if (draw) {
            award(server, duel, duel.getPlayer1(), Outcome.DRAW, 0, List.of(duel.getPlayer2()));
            award(server, duel, duel.getPlayer2(), Outcome.DRAW, 0, List.of(duel.getPlayer1()));
        } else {
            award(server, duel, winner, Outcome.WIN, winnerElo, List.of(loser));
            award(server, duel, loser, Outcome.LOSS, loserElo, List.of(winner));
        }
    }

    public static void awardTeamDuel(MinecraftServer server, ActiveTeamDuel duel, boolean draw,
                                     List<UUID> winners, List<UUID> losers, int winnerElo, int loserElo) {
        if (draw) {
            for (UUID p : duel.getTeam1()) award(server, duel, p, Outcome.DRAW, 0, duel.getTeam2());
            for (UUID p : duel.getTeam2()) award(server, duel, p, Outcome.DRAW, 0, duel.getTeam1());
        } else {
            for (UUID w : winners) award(server, duel, w, Outcome.WIN, winnerElo, losers);
            for (UUID l : losers) award(server, duel, l, Outcome.LOSS, loserElo, winners);
        }
    }

    private static void award(MinecraftServer server, ActiveMatch match, UUID player,
                              Outcome outcome, int eloGain, Collection<UUID> opponents) {
        MatchmakingConfigManager.RewardConfig cfg = MatchmakingConfigManager.getConfig().rewards;
        if (cfg == null || !cfg.enabled || !match.isMatchmaking() || !EconomyBridge.isAvailable()) {
            return;
        }

        long durationSec = matchDurationSeconds(match);
        if (durationSec < cfg.minDurationSeconds) {
            return; // quality gate: instant-forfeit / AFK pays nothing
        }
        long base = switch (outcome) {
            case WIN -> cfg.winReward;
            case LOSS -> cfg.lossReward;
            case DRAW -> cfg.drawReward;
        };
        if (base <= 0) {
            return;
        }

        double quality = clamp(durationSec / (double) Math.max(1, cfg.fullRewardSeconds), 0.0, 1.0);
        double amount = base * quality;
        if (outcome == Outcome.WIN) {
            amount *= eloGateFactor(eloGain, cfg);
        }

        BattleRewardStore store = BattleRewardStore.get(server);
        long today = BattleRewardStore.today();
        int priorMatches = store.maxOpponentCount(player, opponents, today);
        amount *= Math.pow(Math.max(0, cfg.sameOpponentDecayPercent) / 100.0, priorMatches);

        long payout = Math.round(amount);
        long cap = switch (outcome) {
            case WIN -> cfg.winDailyCap;
            case LOSS -> cfg.lossDailyCap;
            case DRAW -> cfg.drawDailyCap;
        };
        long remaining = cap - store.earned(player, outcome.ordinal(), today);
        payout = Math.min(payout, Math.max(0, remaining));
        if (payout <= 0) {
            return;
        }

        EconomyBridge.deliver(player, payout, "DUEL_" + outcome.name());
        store.record(player, outcome.ordinal(), payout, opponents, today);

        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            online.sendSystemMessage(Component.translatable("duels_ld.reward.earned", EconomyBridge.format(payout))
                .withStyle(ChatFormatting.GOLD));
            sendStatus(online);
        }
    }

    /** Pushes the player's current daily earned / cap to the client for the matchmaking screen. */
    public static void sendStatus(ServerPlayer player) {
        MatchmakingConfigManager.RewardConfig cfg = MatchmakingConfigManager.getConfig().rewards;
        boolean enabled = cfg != null && cfg.enabled && EconomyBridge.isAvailable();
        long earned = enabled
            ? BattleRewardStore.get(player.server).totalEarned(player.getUUID(), BattleRewardStore.today())
            : 0L;
        long cap = cfg == null ? 0L : cfg.winDailyCap + cfg.lossDailyCap + cfg.drawDailyCap;
        ServerPlayNetworking.send(player, new RewardStatusPayload(enabled, earned, cap));
    }

    private static long matchDurationSeconds(ActiveMatch match) {
        if (match.isCountdown() || match.getStartTime() <= 0) {
            return 0;
        }
        return (System.currentTimeMillis() - match.getStartTime() - match.getPausedMs()) / 1000L;
    }

    private static double eloGateFactor(int eloGain, MatchmakingConfigManager.RewardConfig cfg) {
        double factor = eloGain / (double) Math.max(1, cfg.eloGateReferenceGain);
        return clamp(factor, Math.max(0, cfg.eloGateMinPercent) / 100.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
