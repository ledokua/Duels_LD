package net.ledok.duels_ld.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StatsManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DuelsLdMod.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<UUID, PlayerStats> playerStats = new ConcurrentHashMap<>();
    private static File statsFile;

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            statsFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("duel_stats.json").toFile();
            loadStats();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveStats());
    }

    public static PlayerStats getStats(UUID playerUUID) {
        return playerStats.computeIfAbsent(playerUUID, k -> new PlayerStats());
    }

    public static void recordWin(UUID playerUUID) {
        getStats(playerUUID).addWin();
        saveStats();
    }

    public static void recordLoss(UUID playerUUID) {
        getStats(playerUUID).addLoss();
        saveStats();
    }

    public static void recordDraw(UUID playerUUID) {
        getStats(playerUUID).addDraw();
        saveStats();
    }

    private static void loadStats() {
        if (statsFile == null || !statsFile.exists()) {
            return;
        }
        try (FileReader reader = new FileReader(statsFile)) {
            Type type = new TypeToken<ConcurrentHashMap<UUID, PlayerStats>>(){}.getType();
            playerStats = GSON.fromJson(reader, type);
            if (playerStats == null) {
                playerStats = new ConcurrentHashMap<>();
            }
            LOGGER.info("Duel stats loaded successfully.");
        } catch (IOException e) {
            LOGGER.error("Failed to load duel stats.", e);
        }
    }

    private static void saveStats() {
        if (statsFile == null) {
            return;
        }
        try (FileWriter writer = new FileWriter(statsFile)) {
            GSON.toJson(playerStats, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save duel stats.", e);
        }
    }
}
