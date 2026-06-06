package net.ledok.duels_ld.manager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class PlayerStats {
    private int wins;
    private int losses;
    private int draws;

    public static final Codec<PlayerStats> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.INT.optionalFieldOf("wins", 0).forGetter(PlayerStats::getWins),
        Codec.INT.optionalFieldOf("losses", 0).forGetter(PlayerStats::getLosses),
        Codec.INT.optionalFieldOf("draws", 0).forGetter(PlayerStats::getDraws)
    ).apply(i, PlayerStats::new));

    public PlayerStats() {
        this.wins = 0;
        this.losses = 0;
        this.draws = 0;
    }

    public PlayerStats(int wins, int losses, int draws) {
        this.wins = wins;
        this.losses = losses;
        this.draws = draws;
    }

    public int getWins() {
        return wins;
    }

    public void addWin() {
        this.wins++;
    }

    public int getLosses() {
        return losses;
    }

    public void addLoss() {
        this.losses++;
    }

    public int getDraws() {
        return draws;
    }

    public void addDraw() {
        this.draws++;
    }
}
