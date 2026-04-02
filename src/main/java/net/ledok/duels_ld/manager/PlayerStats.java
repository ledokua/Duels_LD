package net.ledok.duels_ld.manager;

public class PlayerStats {
    private int wins;
    private int losses;
    private int draws;

    public PlayerStats() {
        this.wins = 0;
        this.losses = 0;
        this.draws = 0;
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
