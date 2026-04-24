package net.ledok.duels_ld.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MMRManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DuelsLdMod.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<UUID, Integer> mmr1v1 = new ConcurrentHashMap<>();
    private static Map<UUID, Integer> mmr2v2 = new ConcurrentHashMap<>();
    private static File mmrFile;
    private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "duels-mmr-save");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean dirty = new AtomicBoolean(false);
    private static int tickCounter = 0;

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            mmrFile = getModDataDir(server).resolve("mmr.json").toFile();
            load();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter >= 600) {
                tickCounter = 0;
                flushIfDirtyAsync();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> flushNow());
    }

    public static int getRating1v1(UUID player) {
        return mmr1v1.computeIfAbsent(player, k -> MatchmakingConfigManager.getConfig().elo.startingRating);
    }

    public static int getRating2v2(UUID player) {
        return mmr2v2.computeIfAbsent(player, k -> MatchmakingConfigManager.getConfig().elo.startingRating);
    }

    public static Map<UUID, Integer> getAll1v1Ratings() {
        return new ConcurrentHashMap<>(mmr1v1);
    }

    public static Map<UUID, Integer> getAll2v2Ratings() {
        return new ConcurrentHashMap<>(mmr2v2);
    }

    public static void applyResult1v1(UUID winner, UUID loser) {
        applyResult1v1WithDelta(winner, loser);
    }

    public static EloDelta1v1 applyResult1v1WithDelta(UUID winner, UUID loser) {
        int ra = getRating1v1(winner);
        int rb = getRating1v1(loser);
        int k = MatchmakingConfigManager.getConfig().elo.kFactor;
        double ea = expectedScore(ra, rb);
        double eb = expectedScore(rb, ra);
        int newRa = (int) Math.round(ra + k * (1.0 - ea));
        int newRb = (int) Math.round(rb + k * (0.0 - eb));
        mmr1v1.put(winner, newRa);
        mmr1v1.put(loser, newRb);
        markDirty();
        return new EloDelta1v1(newRa - ra, newRb - rb);
    }

    public static void applyResult2v2(UUID winner1, UUID winner2, UUID loser1, UUID loser2) {
        applyResult2v2WithDelta(winner1, winner2, loser1, loser2);
    }

    public static EloDelta2v2 applyResult2v2WithDelta(UUID winner1, UUID winner2, UUID loser1, UUID loser2) {
        int r1 = getRating2v2(winner1);
        int r2 = getRating2v2(winner2);
        int l1 = getRating2v2(loser1);
        int l2 = getRating2v2(loser2);

        double teamW = (r1 + r2) / 2.0;
        double teamL = (l1 + l2) / 2.0;
        int k = MatchmakingConfigManager.getConfig().elo.kFactor;

        double ew = expectedScore(teamW, teamL);
        double el = expectedScore(teamL, teamW);

        int deltaW = (int) Math.round(k * (1.0 - ew));
        int deltaL = (int) Math.round(k * (0.0 - el));

        mmr2v2.put(winner1, r1 + deltaW);
        mmr2v2.put(winner2, r2 + deltaW);
        mmr2v2.put(loser1, l1 + deltaL);
        mmr2v2.put(loser2, l2 + deltaL);
        markDirty();
        return new EloDelta2v2(deltaW, deltaL);
    }

    private static double expectedScore(double ra, double rb) {
        return 1.0 / (1.0 + Math.pow(10.0, (rb - ra) / 400.0));
    }

    private static void load() {
        mmr1v1.clear();
        mmr2v2.clear();
        if (mmrFile == null || !mmrFile.exists()) {
            return;
        }
        try (FileReader reader = new FileReader(mmrFile)) {
            Type type = new TypeToken<MMRFile>(){}.getType();
            MMRFile data = GSON.fromJson(reader, type);
            if (data != null) {
                if (data.mmr1v1 != null) {
                    mmr1v1.putAll(data.mmr1v1);
                }
                if (data.mmr2v2 != null) {
                    mmr2v2.putAll(data.mmr2v2);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load MMR data.", e);
        }
    }

    private static void markDirty() {
        dirty.set(true);
    }

    private static void flushIfDirtyAsync() {
        if (dirty.getAndSet(false)) {
            Map<UUID, Integer> snapshot1v1 = new ConcurrentHashMap<>(mmr1v1);
            Map<UUID, Integer> snapshot2v2 = new ConcurrentHashMap<>(mmr2v2);
            SAVE_EXECUTOR.execute(() -> saveSnapshot(snapshot1v1, snapshot2v2));
        }
    }

    private static void flushNow() {
        dirty.set(false);
        Map<UUID, Integer> snapshot1v1 = new ConcurrentHashMap<>(mmr1v1);
        Map<UUID, Integer> snapshot2v2 = new ConcurrentHashMap<>(mmr2v2);
        try {
            SAVE_EXECUTOR.submit(() -> saveSnapshot(snapshot1v1, snapshot2v2)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for async MMR save during shutdown.");
        } catch (Exception e) {
            LOGGER.error("Failed to flush MMR data during shutdown.", e);
        }
    }

    private static void saveSnapshot(Map<UUID, Integer> snapshot1v1, Map<UUID, Integer> snapshot2v2) {
        if (mmrFile == null) {
            return;
        }
        MMRFile data = new MMRFile();
        data.mmr1v1 = snapshot1v1;
        data.mmr2v2 = snapshot2v2;
        try (FileWriter writer = new FileWriter(mmrFile)) {
            GSON.toJson(data, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save MMR data.", e);
        }
    }

    private static java.nio.file.Path getModDataDir(MinecraftServer server) {
        java.nio.file.Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("duels_ld");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.error("Failed to create mod data directory.", e);
        }
        return dir;
    }

    private static class MMRFile {
        public Map<UUID, Integer> mmr1v1;
        public Map<UUID, Integer> mmr2v2;
    }

    public static class EloDelta1v1 {
        public final int winnerDelta;
        public final int loserDelta;

        public EloDelta1v1(int winnerDelta, int loserDelta) {
            this.winnerDelta = winnerDelta;
            this.loserDelta = loserDelta;
        }
    }

    public static class EloDelta2v2 {
        public final int winnerDelta;
        public final int loserDelta;

        public EloDelta2v2(int winnerDelta, int loserDelta) {
            this.winnerDelta = winnerDelta;
            this.loserDelta = loserDelta;
        }
    }
}
