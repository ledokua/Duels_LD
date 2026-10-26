package net.ledok.duels_ld.manager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A player's results. {@code wins}/{@code losses}/{@code draws} are the all-time totals (including
 * matches from before 1.4.0); the per-mode counters were added in 1.4.0 and start at 0.
 */
public class PlayerStats {
    private int wins;
    private int losses;
    private int draws;
    // Per mode, indexed by team size - 1 (0 = 1v1, 1 = 2v2).
    private final int[] modeWins = new int[2];
    private final int[] modeLosses = new int[2];
    private final int[] modeDraws = new int[2];

    public static final Codec<PlayerStats> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.INT.optionalFieldOf("wins", 0).forGetter(PlayerStats::getWins),
        Codec.INT.optionalFieldOf("losses", 0).forGetter(PlayerStats::getLosses),
        Codec.INT.optionalFieldOf("draws", 0).forGetter(PlayerStats::getDraws),
        Codec.INT.optionalFieldOf("wins1v1", 0).forGetter(s -> s.modeWins[0]),
        Codec.INT.optionalFieldOf("losses1v1", 0).forGetter(s -> s.modeLosses[0]),
        Codec.INT.optionalFieldOf("draws1v1", 0).forGetter(s -> s.modeDraws[0]),
        Codec.INT.optionalFieldOf("wins2v2", 0).forGetter(s -> s.modeWins[1]),
        Codec.INT.optionalFieldOf("losses2v2", 0).forGetter(s -> s.modeLosses[1]),
        Codec.INT.optionalFieldOf("draws2v2", 0).forGetter(s -> s.modeDraws[1])
    ).apply(i, (w, l, d, w1, l1, d1, w2, l2, d2) -> {
        PlayerStats stats = new PlayerStats(w, l, d);
        stats.modeWins[0] = w1;
        stats.modeLosses[0] = l1;
        stats.modeDraws[0] = d1;
        stats.modeWins[1] = w2;
        stats.modeLosses[1] = l2;
        stats.modeDraws[1] = d2;
        return stats;
    }));

    public PlayerStats() {
        this(0, 0, 0);
    }

    public PlayerStats(int wins, int losses, int draws) {
        this.wins = wins;
        this.losses = losses;
        this.draws = draws;
    }

    public int getWins() {
        return wins;
    }

    public int getLosses() {
        return losses;
    }

    public int getDraws() {
        return draws;
    }

    public int getWins(int teamSize) {
        return modeWins[index(teamSize)];
    }

    public int getLosses(int teamSize) {
        return modeLosses[index(teamSize)];
    }

    public int getDraws(int teamSize) {
        return modeDraws[index(teamSize)];
    }

    public int getPlayed(int teamSize) {
        return getWins(teamSize) + getLosses(teamSize) + getDraws(teamSize);
    }

    public void addWin(int teamSize) {
        wins++;
        modeWins[index(teamSize)]++;
    }

    public void addLoss(int teamSize) {
        losses++;
        modeLosses[index(teamSize)]++;
    }

    public void addDraw(int teamSize) {
        draws++;
        modeDraws[index(teamSize)]++;
    }

    private static int index(int teamSize) {
        return teamSize >= 2 ? 1 : 0;
    }
}
