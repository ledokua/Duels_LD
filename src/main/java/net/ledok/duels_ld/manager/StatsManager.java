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
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player win/loss/draw records, persisted as world {@link StatsSavedData}. The live map is the
 * SavedData's own map; mutating a record and calling {@code setDirty()} is enough — Minecraft writes
 * it out on the world save. The legacy {@code duels_ld/duel_stats.json} is imported once.
 */
public class StatsManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DuelsLdMod.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static MinecraftServer server;
    private static StatsSavedData store;
    private static Map<UUID, PlayerStats> playerStats = new ConcurrentHashMap<>();
    private static File legacyFile;

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(srv -> {
            server = srv;
            legacyFile = srv.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("duels_ld").resolve("duel_stats.json").toFile();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(srv -> load());
    }

    private static void load() {
        store = StatsSavedData.get(server);
        playerStats = store.stats();
        if (playerStats.isEmpty() && legacyFile != null && legacyFile.exists()) {
            try (FileReader reader = new FileReader(legacyFile)) {
                Type type = new TypeToken<ConcurrentHashMap<UUID, PlayerStats>>(){}.getType();
                Map<UUID, PlayerStats> legacy = GSON.fromJson(reader, type);
                if (legacy != null) {
                    playerStats.putAll(legacy);
                    store.setDirty();
                    LOGGER.info("Imported {} player stat record(s) from legacy duel_stats.json.", legacy.size());
                }
            } catch (IOException e) {
                LOGGER.error("Failed to import legacy duel stats.", e);
            }
        }
    }

    /** The player's record, or null if they never finished a match. Never creates one. */
    public static PlayerStats find(UUID playerUUID) {
        return playerStats.get(playerUUID);
    }

    /** A copy of every record (for leaderboards). */
    public static Map<UUID, PlayerStats> all() {
        return Map.copyOf(playerStats);
    }

    public static void recordWin(UUID playerUUID, int teamSize) {
        record(playerUUID).addWin(teamSize);
        markDirty();
    }

    public static void recordLoss(UUID playerUUID, int teamSize) {
        record(playerUUID).addLoss(teamSize);
        markDirty();
    }

    public static void recordDraw(UUID playerUUID, int teamSize) {
        record(playerUUID).addDraw(teamSize);
        markDirty();
    }

    private static PlayerStats record(UUID playerUUID) {
        return playerStats.computeIfAbsent(playerUUID, k -> new PlayerStats());
    }

    private static void markDirty() {
        if (store != null) {
            store.setDirty();
        }
    }
}
