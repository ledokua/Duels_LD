package net.ledok.duels_ld.manager;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.network.chat.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class ActiveDuel {
    private final UUID player1;
    private final UUID player2;
    private final DuelSettings settings;
    private final ServerBossEvent bossBar;
    private long startTime;
    private boolean isCountdown;
    private int countdownSeconds = 10;
    private int countdownTicks = 0;
    private String teamName;
    
    private float player1StartHp;
    private float player2StartHp;

    private final Map<UUID, MatchPoints> points = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastHealth = new ConcurrentHashMap<>();
    private final Map<UUID, net.minecraft.world.phys.Vec3> spawnPositions = new ConcurrentHashMap<>();
    private final Map<UUID, net.minecraft.world.phys.Vec3> freezePositions = new ConcurrentHashMap<>();
    private String arenaName;
    private boolean matchmaking;
    private volatile EndRequest pendingEndRequest;

    public ActiveDuel(UUID player1, UUID player2, DuelSettings settings, String teamName, float p1Hp, float p2Hp) {
        this.player1 = player1;
        this.player2 = player2;
        this.settings = settings;
        this.teamName = teamName;
        this.isCountdown = true;
        this.player1StartHp = p1Hp;
        this.player2StartHp = p2Hp;

        points.put(player1, new MatchPoints());
        points.put(player2, new MatchPoints());
        lastHealth.put(player1, p1Hp);
        lastHealth.put(player2, p2Hp);
        
        this.bossBar = new ServerBossEvent(
            Component.translatable("duels_ld.duel.boss_starting", countdownSeconds),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.PROGRESS
        );
    }

    public UUID getPlayer1() {
        return player1;
    }

    public UUID getPlayer2() {
        return player2;
    }

    public DuelSettings getSettings() {
        return settings;
    }

    public ServerBossEvent getBossBar() {
        return bossBar;
    }

    public boolean isCountdown() {
        return isCountdown;
    }

    public void setCountdown(boolean countdown) {
        isCountdown = countdown;
    }

    public int getCountdownSeconds() {
        return countdownSeconds;
    }

    public void decrementCountdown() {
        countdownSeconds--;
    }
    
    public int getCountdownTicks() {
        return countdownTicks;
    }
    
    public void incrementCountdownTicks() {
        countdownTicks++;
    }
    
    public void resetCountdownTicks() {
        countdownTicks = 0;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public String getTeamName() {
        return teamName;
    }
    
    public float getPlayer1StartHp() {
        return player1StartHp;
    }
    
    public float getPlayer2StartHp() {
        return player2StartHp;
    }

    public String getArenaName() {
        return arenaName;
    }

    public void setArenaName(String arenaName) {
        this.arenaName = arenaName;
    }

    public boolean isMatchmaking() {
        return matchmaking;
    }

    public void setMatchmaking(boolean matchmaking) {
        this.matchmaking = matchmaking;
    }

    public void setSpawnPosition(UUID player, net.minecraft.world.phys.Vec3 pos) {
        spawnPositions.put(player, pos);
    }

    public net.minecraft.world.phys.Vec3 getSpawnPosition(UUID player) {
        return spawnPositions.get(player);
    }

    public void setFreezePosition(UUID player, net.minecraft.world.phys.Vec3 pos) {
        freezePositions.put(player, pos);
    }

    public net.minecraft.world.phys.Vec3 getFreezePosition(UUID player) {
        return freezePositions.get(player);
    }

    public void markForEnd(boolean draw, UUID winnerUUID) {
        pendingEndRequest = new EndRequest(draw, winnerUUID);
    }

    public EndRequest consumePendingEndRequest() {
        EndRequest request = pendingEndRequest;
        pendingEndRequest = null;
        return request;
    }

    public MatchPoints getPoints(UUID player) {
        return points.computeIfAbsent(player, k -> new MatchPoints());
    }

    public Map<UUID, MatchPoints> getAllPoints() {
        return points;
    }

    public float getLastHealth(UUID player) {
        return lastHealth.getOrDefault(player, 0.0f);
    }

    public void setLastHealth(UUID player, float health) {
        lastHealth.put(player, health);
    }

    public static final class EndRequest {
        private final boolean draw;
        private final UUID winnerUUID;

        public EndRequest(boolean draw, UUID winnerUUID) {
            this.draw = draw;
            this.winnerUUID = winnerUUID;
        }

        public boolean isDraw() {
            return draw;
        }

        public UUID getWinnerUUID() {
            return winnerUUID;
        }
    }
}
