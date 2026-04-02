package net.ledok.duels_ld.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ArenaManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DuelsLdMod.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File arenasFile;
    private static final Map<String, Arena> arenas = new HashMap<>();
    private static final Set<String> activeArenas = new HashSet<>();
    private static MinecraftServer server;

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(srv -> {
            server = srv;
            arenasFile = srv.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("duel_arenas.json").toFile();
            load();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> save());
    }

    public static boolean createArena(String name, ResourceKey<Level> dimension) {
        if (arenas.containsKey(name)) {
            return false;
        }
        arenas.put(name, new Arena(name, dimension.location().toString()));
        save();
        return true;
    }

    public static boolean removeArena(String name) {
        if (!arenas.containsKey(name)) {
            return false;
        }
        arenas.remove(name);
        activeArenas.remove(name);
        save();
        return true;
    }

    public static Arena getArena(String name) {
        return arenas.get(name);
    }

    public static List<Arena> listArenas() {
        List<Arena> list = new ArrayList<>(arenas.values());
        list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return list;
    }

    public static void setPos1(String name, BlockPos pos) {
        Arena arena = arenas.get(name);
        if (arena == null) return;
        arena.pos1 = pos;
        save();
    }

    public static void setPos2(String name, BlockPos pos) {
        Arena arena = arenas.get(name);
        if (arena == null) return;
        arena.pos2 = pos;
        save();
    }

    public static boolean addSpawn(String name, int team, BlockPos pos) {
        Arena arena = arenas.get(name);
        if (arena == null) return false;
        if (team == 1) {
            arena.team1Spawns.add(pos);
        } else if (team == 2) {
            arena.team2Spawns.add(pos);
        } else {
            return false;
        }
        save();
        return true;
    }

    public static boolean removeSpawn(String name, int team, BlockPos pos) {
        Arena arena = arenas.get(name);
        if (arena == null) return false;
        boolean removed;
        if (team == 1) {
            removed = arena.team1Spawns.remove(pos);
        } else if (team == 2) {
            removed = arena.team2Spawns.remove(pos);
        } else {
            return false;
        }
        if (removed) {
            save();
        }
        return removed;
    }

    public static boolean isActive(String name) {
        return activeArenas.contains(name);
    }

    public static void markActive(String name) {
        activeArenas.add(name);
    }

    public static void markInactive(String name) {
        activeArenas.remove(name);
    }

    public static Arena pickArena(int teamSize) {
        List<Arena> candidates = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            if (isActive(arena.name)) continue;
            if (teamSize == 1) {
                if (arena.team1Spawns.size() >= 1 && arena.team2Spawns.size() >= 1) {
                    candidates.add(arena);
                }
            } else {
                if (arena.team1Spawns.size() >= teamSize && arena.team2Spawns.size() >= teamSize) {
                    candidates.add(arena);
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Collections.shuffle(candidates);
        return candidates.get(0);
    }

    public static List<BlockPos> pickSpawns(Arena arena, int team, int count) {
        if (arena == null) return List.of();
        List<BlockPos> spawns = new ArrayList<>(team == 1 ? arena.team1Spawns : arena.team2Spawns);
        if (spawns.size() < count) {
            return List.of();
        }
        Collections.shuffle(spawns);
        return spawns.subList(0, count);
    }

    public static MinecraftServer getServer() {
        return server;
    }

    private static void load() {
        arenas.clear();
        if (arenasFile == null || !arenasFile.exists()) {
            save();
            return;
        }
        try (FileReader reader = new FileReader(arenasFile)) {
            ArenasFile data = GSON.fromJson(reader, ArenasFile.class);
            if (data != null && data.arenas != null) {
                for (Arena a : data.arenas) {
                    arenas.put(a.name, a);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load arenas.", e);
        }
    }

    private static void save() {
        if (arenasFile == null) {
            return;
        }
        ArenasFile data = new ArenasFile();
        data.arenas = new ArrayList<>(arenas.values());
        try (FileWriter writer = new FileWriter(arenasFile)) {
            GSON.toJson(data, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save arenas.", e);
        }
    }

    public static class Arena {
        public String name;
        public String dimensionId;
        public BlockPos pos1;
        public BlockPos pos2;
        public List<BlockPos> team1Spawns = new ArrayList<>();
        public List<BlockPos> team2Spawns = new ArrayList<>();

        public Arena() {}

        public Arena(String name, String dimensionId) {
            this.name = name;
            this.dimensionId = dimensionId;
        }

        public ResourceKey<Level> getDimensionKey() {
            ResourceLocation id = ResourceLocation.tryParse(dimensionId);
            if (id == null) {
                id = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
            }
            return ResourceKey.create(Registries.DIMENSION, id);
        }
    }

    private static class ArenasFile {
        public List<Arena> arenas = new ArrayList<>();
    }
}
