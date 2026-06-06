package net.ledok.duels_ld.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MMRManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DuelsLdMod.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MinecraftServer server;
    private static MmrSavedData store;
    private static Map<UUID, Integer> mmr1v1 = new ConcurrentHashMap<>();
    private static Map<UUID, Integer> mmr2v2 = new ConcurrentHashMap<>();
    private static File legacyFile;

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(srv -> {
            server = srv;
            legacyFile = srv.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("duels_ld").resolve("mmr.json").toFile();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(srv -> load());
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
        store = MmrSavedData.get(server);
        mmr1v1 = store.map1v1();
        mmr2v2 = store.map2v2();
        if (mmr1v1.isEmpty() && mmr2v2.isEmpty() && legacyFile != null && legacyFile.exists()) {
            try (FileReader reader = new FileReader(legacyFile)) {
                MMRFile data = GSON.fromJson(reader, MMRFile.class);
                if (data != null) {
                    if (data.mmr1v1 != null) {
                        mmr1v1.putAll(data.mmr1v1);
                    }
                    if (data.mmr2v2 != null) {
                        mmr2v2.putAll(data.mmr2v2);
                    }
                    store.setDirty();
                    LOGGER.info("Imported MMR data from legacy mmr.json.");
                }
            } catch (IOException e) {
                LOGGER.error("Failed to import legacy MMR data.", e);
            }
        }
    }

    private static void markDirty() {
        if (store != null) {
            store.setDirty();
        }
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
