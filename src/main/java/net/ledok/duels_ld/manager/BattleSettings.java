package net.ledok.duels_ld.manager;

public class BattleSettings {
    private int durationSeconds = 300; // Default 5 minutes

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }
}
