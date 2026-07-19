package net.ledok.duels_ld.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.ledok.duels_ld.DuelsLdMod;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class MatchmakingConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DuelsLdMod.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File configFile;
    private static MatchmakingConfig config = MatchmakingConfig.defaults();

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            java.nio.file.Path dir = FabricLoader.getInstance().getConfigDir().resolve("duels_ld");
            try {
                java.nio.file.Files.createDirectories(dir);
            } catch (IOException e) {
                LOGGER.error("Failed to create config directory.", e);
            }
            configFile = dir.resolve("matchmaking_config.json").toFile();
            loadConfig();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveConfig());
    }

    public static MatchmakingConfig getConfig() {
        return config;
    }

    public static void updateConfig(MatchmakingConfig newConfig) {
        config = newConfig;
        saveConfig();
    }

    /** Re-reads the config file from disk. Returns false if no file exists yet. */
    public static boolean reload() {
        if (configFile == null || !configFile.exists()) {
            return false;
        }
        loadConfig();
        return true;
    }

    private static void loadConfig() {
        if (configFile == null || !configFile.exists()) {
            saveConfig();
            return;
        }
        try (FileReader reader = new FileReader(configFile)) {
            MatchmakingConfig loaded = GSON.fromJson(reader, MatchmakingConfig.class);
            if (loaded != null) {
                config = loaded;
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load matchmaking config.", e);
        }
    }

    private static void saveConfig() {
        if (configFile == null) {
            return;
        }
        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save matchmaking config.", e);
        }
    }

    public static class MatchmakingConfig {
        // ── Match formats ───────────────────────────────────────────────
        public MatchSettings oneVOne = new MatchSettings(DuelSettings.DEFAULT_DURATION_SECONDS, 0);
        public MatchSettings twoVTwo = new MatchSettings(DuelSettings.DEFAULT_DURATION_SECONDS, 0);

        // ── Battle behavior ─────────────────────────────────────────────
        public BattleConfig battle = new BattleConfig();

        // ── Rating & matchmaking ────────────────────────────────────────
        public EloSettings elo = new EloSettings(32, 1000);
        public int mmrRangeStart = 100;
        public int mmrRangeIncrease = 50;
        public int mmrRangeIncreaseSeconds = 15;
        public int mmrRangeMax = 400;

        // ── Scoring & rewards ───────────────────────────────────────────
        public PointWeights weights = new PointWeights(1.0, 1.0, 1.0, 5.0);
        public RewardConfig rewards = new RewardConfig();

        public static MatchmakingConfig defaults() {
            return new MatchmakingConfig();
        }
    }

    /** Per-battle behavior. */
    public static class BattleConfig {
        // Pre-fight countdown length in seconds (the "starting in N" before FIGHT).
        public int countdownSeconds = 10;
        // How long a disconnected player has to reconnect before forfeiting (timer pauses meanwhile).
        public int reconnectGraceSeconds = 60;
        // Heal players to full and refill hunger when the battle starts.
        public boolean healAndFeedAtStart = true;
        // Extra max health granted for the duration of a battle, applied as an additive
        // generic.max_health attribute modifier (20 = +10 hearts on top of the vanilla 20).
        public double extraHealth = 20.0;
        // Game mode applied during a battle (e.g. ADVENTURE or SURVIVAL for block-breaking arenas).
        public String inBattleGameMode = "ADVENTURE";
        // Items that may not be used during an arena match. Entries are item ids
        // (e.g. "minecraft:ender_pearl") or item tags prefixed with '#' (e.g. "#minecraft:beds").
        public java.util.List<String> arenaItemBlacklist = new java.util.ArrayList<>(java.util.List.of(
            "minecraft:ender_pearl",
            "minecraft:chorus_fruit"
        ));
        // Keep enforcing the blacklist this long after a match ends (blocks last-second use).
        public int itemBlacklistGraceSeconds = 2;
        // Potion/mob effects stripped from players during a match (e.g. "minecraft:speed",
        // "galosphere:astral"). Removed every tick, so they can't be applied mid-fight either.
        public java.util.List<String> arenaEffectBlacklist = new java.util.ArrayList<>();
        // After a match ends, if a delayed teleport (e.g. an ender pearl thrown right before the end)
        // pulls a player back inside the arena within this window, teleport them back out.
        public int postMatchTeleportGuardSeconds = 5;
        // Penalty for leaving the arena bounds (deters using out-of-bounds to teleport to spawn).
        // When a player is returned to their spawn for leaving, they take this % of their max health
        // as unblockable true damage (never lethal) and lose this % of their current match points.
        public double outOfBoundsDamagePercent = 10.0;
        public double outOfBoundsPointPenaltyPercent = 25.0;
    }

    /** Currency rewards for matchmade battles (paid via Economy_LD), with anti-farm limits. */
    public static class RewardConfig {
        public boolean enabled = true;
        public long winReward = 100;
        public long lossReward = 25;
        public long drawReward = 40;
        // Quality gate: no reward below minDurationSeconds; the reward ramps linearly to full at
        // fullRewardSeconds (so AFK / instant-forfeit matches pay little or nothing).
        public int minDurationSeconds = 20;
        public int fullRewardSeconds = 60;
        // A drawn match pays nothing unless the combined points (offense + defense + support) of all
        // participants reach this, so standing still until the timer (a 0-0 stalemate) earns nothing.
        public double drawMinPoints = 10.0;
        // Hard ceiling on battle currency a player can earn per day, per outcome.
        public long winDailyCap = 800;
        public long lossDailyCap = 150;
        public long drawDailyCap = 100;
        // Each repeat match vs the same opponent (within a day) multiplies the reward by this %.
        public int sameOpponentDecayPercent = 50;
        // Win Elo gain at which the gate factor is 1.0; smaller gains (stomping weak opponents)
        // scale down to eloGateMinPercent.
        public int eloGateReferenceGain = 16;
        public int eloGateMinPercent = 20;
    }

    public static class MatchSettings {
        public int durationSeconds;
        public int winHpPercentage;

        public MatchSettings() {}

        public MatchSettings(int durationSeconds, int winHpPercentage) {
            this.durationSeconds = durationSeconds;
            this.winHpPercentage = winHpPercentage;
        }
    }

    public static class PointWeights {
        public double offensePerDamage;
        public double supportPerHeal;
        public double defensePerBlocked;
        public double killBonus;

        public PointWeights() {}

        public PointWeights(double offensePerDamage, double supportPerHeal, double defensePerBlocked, double killBonus) {
            this.offensePerDamage = offensePerDamage;
            this.supportPerHeal = supportPerHeal;
            this.defensePerBlocked = defensePerBlocked;
            this.killBonus = killBonus;
        }
    }

    public static class EloSettings {
        public int kFactor;
        public int startingRating;

        public EloSettings() {}

        public EloSettings(int kFactor, int startingRating) {
            this.kFactor = kFactor;
            this.startingRating = startingRating;
        }
    }
}
