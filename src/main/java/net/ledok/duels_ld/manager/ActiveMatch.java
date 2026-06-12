package net.ledok.duels_ld.manager;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State shared by every active match (1v1 {@link ActiveDuel} and team {@link ActiveTeamDuel}):
 * the boss bar, countdown, timers, arena binding, and all per-player data (held in one
 * {@code Map<UUID, Participant>}). Subclasses only add their team layout and end-condition.
 */
public abstract class ActiveMatch {
    protected final Map<UUID, Participant> participants = new ConcurrentHashMap<>();

    private final DuelSettings settings;
    private final ServerBossEvent bossBar;
    private long startTime;
    private boolean countdown = true;
    private final int initialCountdownSeconds;
    private int countdownSeconds;
    private int countdownTicks = 0;
    private String arenaName;
    private boolean matchmaking;
    private long pausedMs = 0L;
    private long lastTickMs = 0L;

    protected ActiveMatch(DuelSettings settings) {
        this.settings = settings;
        this.initialCountdownSeconds = Math.max(1, MatchmakingConfigManager.getConfig().battle.countdownSeconds);
        this.countdownSeconds = this.initialCountdownSeconds;
        this.bossBar = new ServerBossEvent(
            Component.translatable("duels_ld.duel.boss_starting", countdownSeconds),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.PROGRESS
        );
    }

    protected void addParticipant(UUID id, int team, float startHealth) {
        Participant p = new Participant();
        p.team = team;
        p.lastHealth = startHealth;
        participants.put(id, p);
    }

    protected Participant participant(UUID id) {
        return participants.computeIfAbsent(id, k -> new Participant());
    }

    // ── Match-wide state ───────────────────────────────────────────────────
    public DuelSettings getSettings() { return settings; }
    public ServerBossEvent getBossBar() { return bossBar; }

    public boolean isCountdown() { return countdown; }
    public void setCountdown(boolean countdown) { this.countdown = countdown; }
    public int getCountdownSeconds() { return countdownSeconds; }
    public int getInitialCountdownSeconds() { return initialCountdownSeconds; }
    public void decrementCountdown() { countdownSeconds--; }
    public int getCountdownTicks() { return countdownTicks; }
    public void incrementCountdownTicks() { countdownTicks++; }
    public void resetCountdownTicks() { countdownTicks = 0; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public String getArenaName() { return arenaName; }
    public void setArenaName(String arenaName) { this.arenaName = arenaName; }
    public boolean isMatchmaking() { return matchmaking; }
    public void setMatchmaking(boolean matchmaking) { this.matchmaking = matchmaking; }

    public long getPausedMs() { return pausedMs; }
    public void addPausedMs(long delta) { pausedMs += delta; }

    /** Returns ms since the previous tick (0 on the first call) and records the current time. */
    public long tickDelta(long now) {
        long delta = lastTickMs == 0L ? 0L : now - lastTickMs;
        lastTickMs = now;
        return delta;
    }

    /** Sum of every participant's accumulated points — a proxy for how much actually happened. */
    public double totalPoints() {
        double total = 0.0;
        for (Participant p : participants.values()) {
            total += p.points.getTotal();
        }
        return total;
    }

    // ── Per-player state ───────────────────────────────────────────────────
    public MatchPoints getPoints(UUID player) { return participant(player).points; }

    public float getLastHealth(UUID player) {
        Participant p = participants.get(player);
        return p == null ? 0.0f : p.lastHealth;
    }
    public void setLastHealth(UUID player, float health) { participant(player).lastHealth = health; }

    public void setSpawnPosition(UUID player, Vec3 pos) { participant(player).spawnPosition = pos; }
    public Vec3 getSpawnPosition(UUID player) {
        Participant p = participants.get(player);
        return p == null ? null : p.spawnPosition;
    }

    public void setSpawnFacing(UUID player, float yaw, float pitch) {
        Participant p = participant(player);
        p.spawnYaw = yaw;
        p.spawnPitch = pitch;
    }
    public float getSpawnYaw(UUID player) {
        Participant p = participants.get(player);
        return p == null ? 0.0f : p.spawnYaw;
    }
    public float getSpawnPitch(UUID player) {
        Participant p = participants.get(player);
        return p == null ? 0.0f : p.spawnPitch;
    }

    public void setFreezePosition(UUID player, Vec3 pos) { participant(player).freezePosition = pos; }
    public Vec3 getFreezePosition(UUID player) {
        Participant p = participants.get(player);
        return p == null ? null : p.freezePosition;
    }

    public void markDisconnected(UUID player, long now) {
        Participant p = participant(player);
        if (p.disconnectedAt == null) {
            p.disconnectedAt = now;
        }
    }
    public void clearDisconnected(UUID player) { participant(player).disconnectedAt = null; }
    public Long getDisconnectedAt(UUID player) {
        Participant p = participants.get(player);
        return p == null ? null : p.disconnectedAt;
    }
}
