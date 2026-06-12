package net.ledok.duels_ld.manager;

public class MatchPoints {
    private double offense;
    private double support;
    private double defense;

    public void addOffense(double amount) {
        offense += amount;
    }

    public void addSupport(double amount) {
        support += amount;
    }

    public void addDefense(double amount) {
        defense += amount;
    }

    public double getTotal() {
        return offense + support + defense;
    }

    /** Multiplies every component by {@code factor} (e.g. 0.75 to dock 25% of the player's points). */
    public void scale(double factor) {
        offense *= factor;
        support *= factor;
        defense *= factor;
    }
}
