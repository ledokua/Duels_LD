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
        public MatchSettings oneVOne = new MatchSettings(DuelSettings.DEFAULT_DURATION_SECONDS, 0);
        public MatchSettings twoVTwo = new MatchSettings(DuelSettings.DEFAULT_DURATION_SECONDS, 0);
        public PointWeights weights = new PointWeights(1.0, 1.0, 1.0, 5.0);
        public EloSettings elo = new EloSettings(32, 1000);
        public int mmrRangeStart = 100;
        public int mmrRangeIncrease = 50;
        public int mmrRangeIncreaseSeconds = 15;
        public int mmrRangeMax = 400;

        public static MatchmakingConfig defaults() {
            return new MatchmakingConfig();
        }
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
