package net.ledok.duels_ld.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.ledok.duels_ld.DuelsLdMod;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class StatsManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DuelsLdMod.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<UUID, PlayerStats> playerStats = new ConcurrentHashMap<>();
    private static File statsFile;
    private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "duels-stats-save");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean dirty = new AtomicBoolean(false);
    private static int tickCounter = 0;

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            java.nio.file.Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("duels_ld");
            try {
                java.nio.file.Files.createDirectories(dir);
            } catch (IOException e) {
                LOGGER.error("Failed to create mod data directory.", e);
            }
            statsFile = dir.resolve("duel_stats.json").toFile();
            loadStats();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter >= 600) {
                tickCounter = 0;
                if (dirty.getAndSet(false)) {
                    Map<UUID, PlayerStats> snapshot = new ConcurrentHashMap<>(playerStats);
                    SAVE_EXECUTOR.execute(() -> saveStats(snapshot));
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveStats(playerStats));
    }

    public static PlayerStats getStats(UUID playerUUID) {
        return playerStats.computeIfAbsent(playerUUID, k -> new PlayerStats());
    }

    public static void recordWin(UUID playerUUID) {
        getStats(playerUUID).addWin();
        markDirty();
    }

    public static void recordLoss(UUID playerUUID) {
        getStats(playerUUID).addLoss();
        markDirty();
    }

    public static void recordDraw(UUID playerUUID) {
        getStats(playerUUID).addDraw();
        markDirty();
    }

    private static void markDirty() {
        dirty.set(true);
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

    private static void saveStats(Map<UUID, PlayerStats> snapshot) {
        if (statsFile == null) {
            return;
        }
        try (FileWriter writer = new FileWriter(statsFile)) {
            GSON.toJson(snapshot, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save duel stats.", e);
        }
    }
}
