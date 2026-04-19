package net.ledok.duels_ld.manager;

public class DuelSettings {
    public static final int DEFAULT_DURATION_SECONDS = 120;
    private int durationSeconds = DEFAULT_DURATION_SECONDS;
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
