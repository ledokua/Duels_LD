package net.ledok.duels_ld.manager;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ActiveTeamDuel {
    private final List<UUID> team1;
    private final List<UUID> team2;
    private final DuelSettings settings;
    private final ServerBossEvent bossBar;
    private long startTime;
    private boolean countdown;
    private int countdownSeconds = 10;
    private int countdownTicks = 0;
    private final String teamNameBase;
    private final Set<UUID> eliminated = new HashSet<>();
    private final Map<UUID, Float> startHealth = new ConcurrentHashMap<>();
    private final Map<UUID, MatchPoints> points = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastHealth = new ConcurrentHashMap<>();
    private final Map<UUID, Vec3> spawnPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Vec3> freezePositions = new ConcurrentHashMap<>();
    private String arenaName;
    private boolean matchmaking;
    private volatile EndRequest pendingEndRequest;

    public ActiveTeamDuel(List<UUID> team1, List<UUID> team2, DuelSettings settings, String teamNameBase,
                          Map<UUID, Float> startHealth) {
        this.team1 = new ArrayList<>(team1);
        this.team2 = new ArrayList<>(team2);
        this.settings = settings;
        this.teamNameBase = teamNameBase;
        this.countdown = true;
        this.startHealth.putAll(startHealth);
        for (UUID id : allPlayers()) {
            points.put(id, new MatchPoints());
            lastHealth.put(id, this.startHealth.getOrDefault(id, 20.0f));
        }
        this.bossBar = new ServerBossEvent(
            Component.translatable("duels_ld.duel.boss_starting", countdownSeconds),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.PROGRESS
        );
    }

    public List<UUID> getTeam1() {
        return team1;
    }

    public List<UUID> getTeam2() {
        return team2;
    }

    public List<UUID> allPlayers() {
        List<UUID> players = new ArrayList<>(team1.size() + team2.size());
        players.addAll(team1);
        players.addAll(team2);
        return players;
    }

    public boolean isParticipant(UUID player) {
        return team1.contains(player) || team2.contains(player);
    }

    public int getTeam(UUID player) {
        if (team1.contains(player)) {
            return 1;
        }
        if (team2.contains(player)) {
            return 2;
        }
        return 0;
    }

    public boolean isEliminated(UUID player) {
        return eliminated.contains(player);
    }

    public void eliminate(UUID player) {
        eliminated.add(player);
    }

    public boolean hasAlivePlayersInTeam(int team) {
        List<UUID> ids = team == 1 ? team1 : team2;
        for (UUID id : ids) {
            if (!eliminated.contains(id)) {
                return true;
            }
        }
        return false;
    }

    public ServerBossEvent getBossBar() {
        return bossBar;
    }

    public DuelSettings getSettings() {
        return settings;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public boolean isCountdown() {
        return countdown;
    }

    public void setCountdown(boolean countdown) {
        this.countdown = countdown;
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

    public String getTeamNameBase() {
        return teamNameBase;
    }

    public float getStartHealth(UUID player) {
        return startHealth.getOrDefault(player, 20.0f);
    }

    public MatchPoints getPoints(UUID player) {
        return points.computeIfAbsent(player, k -> new MatchPoints());
    }

    public float getLastHealth(UUID player) {
        return lastHealth.getOrDefault(player, 0.0f);
    }

    public void setLastHealth(UUID player, float health) {
        lastHealth.put(player, health);
    }

    public void setSpawnPosition(UUID player, Vec3 pos) {
        spawnPositions.put(player, pos);
    }

    public Vec3 getSpawnPosition(UUID player) {
        return spawnPositions.get(player);
    }

    public void setFreezePosition(UUID player, Vec3 pos) {
        freezePositions.put(player, pos);
    }

    public Vec3 getFreezePosition(UUID player) {
        return freezePositions.get(player);
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

    public void markForEnd(boolean draw, int winningTeam) {
        pendingEndRequest = new EndRequest(draw, winningTeam);
    }

    public EndRequest consumePendingEndRequest() {
        EndRequest request = pendingEndRequest;
        pendingEndRequest = null;
        return request;
    }

    public static final class EndRequest {
        private final boolean draw;
        private final int winningTeam;

        public EndRequest(boolean draw, int winningTeam) {
            this.draw = draw;
            this.winningTeam = winningTeam;
        }

        public boolean isDraw() {
            return draw;
        }

        public int getWinningTeam() {
            return winningTeam;
        }
    }
}
