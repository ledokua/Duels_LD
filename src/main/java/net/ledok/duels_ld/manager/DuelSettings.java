package net.ledok.duels_ld.manager;

public class DuelSettings {
    private int durationSeconds = 120; // Default 2 minutes
    private int winHpPercentage = 0; // Default 0% (death)

    public DuelSettings() {}

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public int getWinHpPercentage() {
        return winHpPercentage;
    }

    public void setWinHpPercentage(int winHpPercentage) {
        this.winHpPercentage = winHpPercentage;
    }
}
