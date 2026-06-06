package net.ledok.duels_ld.manager;

import net.minecraft.world.phys.Vec3;

/**
 * Per-player state within a match: team, scoring, the spawn (with facing) and freeze positions,
 * last-seen health, elimination, and the disconnect timestamp. Replaces the parallel {@code
 * Map<UUID, …>} fields that used to be duplicated across {@link ActiveDuel} and
 * {@link ActiveTeamDuel}; both now share one {@code Map<UUID, Participant>} via {@link ActiveMatch}.
 */
public class Participant {
    int team;
    final MatchPoints points = new MatchPoints();
    float lastHealth;
    Vec3 spawnPosition;
    float spawnYaw;
    float spawnPitch;
    Vec3 freezePosition;
    boolean eliminated;
    Long disconnectedAt; // null when connected
}
