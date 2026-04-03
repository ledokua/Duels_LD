package net.ledok.duels_ld.manager;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ActiveBattle {
    private final Map<String, List<UUID>> teams;
    private final BattleSettings settings;
    private final ServerBossEvent bossBar;
    private long startTime;
    private boolean isCountdown;
    private int countdownSeconds = 10;
    private int countdownTicks = 0;
    
    private final Map<UUID, GameType> playerOriginalGameModes = new ConcurrentHashMap<>();
    private final Map<UUID, Vec3> playerOriginalPositions = new ConcurrentHashMap<>();
    private final Map<UUID, ResourceKey<Level>> playerOriginalDimensions = new ConcurrentHashMap<>();
    private final Set<UUID> eliminatedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, MatchPoints> points = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastHealth = new ConcurrentHashMap<>();
    private final Map<UUID, net.minecraft.world.phys.Vec3> spawnPositions = new ConcurrentHashMap<>();
    private String arenaName;
    private boolean matchmaking;

    public ActiveBattle(Map<String, List<UUID>> teams, BattleSettings settings) {
        this.teams = teams;
        this.settings = settings;
        this.isCountdown = true;
        
        this.bossBar = new ServerBossEvent(
            Component.translatable("duels_ld.battle.boss_starting", countdownSeconds),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.PROGRESS
        );
    }

    public Map<String, List<UUID>> getTeams() {
        return teams;
    }

    public BattleSettings getSettings() {
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
    
    public void addOriginalGameMode(UUID player, GameType gm) {
        playerOriginalGameModes.put(player, gm);
    }
    
    public GameType getOriginalGameMode(UUID player) {
        return playerOriginalGameModes.get(player);
    }
    
    public void addOriginalPosition(UUID player, Vec3 pos, ResourceKey<Level> dim) {
        playerOriginalPositions.put(player, pos);
        playerOriginalDimensions.put(player, dim);
    }
    
    public Vec3 getOriginalPosition(UUID player) {
        return playerOriginalPositions.get(player);
    }
    
    public ResourceKey<Level> getOriginalDimension(UUID player) {
        return playerOriginalDimensions.get(player);
    }
    
    public void eliminatePlayer(UUID player) {
        eliminatedPlayers.add(player);
    }
    
    public boolean isEliminated(UUID player) {
        return eliminatedPlayers.contains(player);
    }
    
    public Set<UUID> getEliminatedPlayers() {
        return eliminatedPlayers;
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
}
