package net.ledok.duels_ld.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        });
        // Load after worlds are ready (SavedData needs the overworld's data storage).
        ServerLifecycleEvents.SERVER_STARTED.register(srv -> load());
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
        // An arena hosting a live match can't be deleted: the running duel resolves it by name
        // every tick for bounds enforcement, so deleting it would silently disable that.
        if (!arenas.containsKey(name) || isActive(name)) {
            return false;
        }
        arenas.remove(name);
        save();
        return true;
    }

    public static Arena getArena(String name) {
        return arenas.get(name);
    }

    /**
     * Renames an arena. Fails if the source is missing, the target name is taken/empty, or the
     * arena is hosting a live match (the running duel holds the old name, so renaming would break
     * its bounds enforcement and leak the active flag).
     */
    public static boolean renameArena(String oldName, String newName) {
        if (newName == null || newName.isEmpty() || oldName.equals(newName) || isActive(oldName)) {
            return false;
        }
        Arena arena = arenas.get(oldName);
        if (arena == null || arenas.containsKey(newName)) {
            return false;
        }
        arenas.remove(oldName);
        arena.name = newName;
        arenas.put(newName, arena);
        save();
        return true;
    }

    /** Adds a spawn at {@code pos}, or removes it if a spawn of that team already exists there. */
    public static boolean toggleSpawn(String name, int team, BlockPos pos, float yaw, float pitch) {
        Arena arena = arenas.get(name);
        if (arena == null) {
            return false;
        }
        List<SpawnPoint> list = team == 1 ? arena.team1Spawns : team == 2 ? arena.team2Spawns : null;
        if (list == null) {
            return false;
        }
        boolean removed = list.removeIf(s -> s.pos().equals(pos));
        if (!removed) {
            list.add(new SpawnPoint(pos, yaw, pitch));
        }
        save();
        return !removed; // true = added, false = removed
    }

    /** True when at least one arena (busy or not) has enough spawns for that team size. */
    public static boolean hasArenaFor(int teamSize) {
        for (Arena arena : arenas.values()) {
            if (isReadyFor(arena, teamSize)) {
                return true;
            }
        }
        return false;
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

    public static boolean addSpawn(String name, int team, BlockPos pos, float yaw, float pitch) {
        Arena arena = arenas.get(name);
        if (arena == null) return false;
        SpawnPoint spawn = new SpawnPoint(pos, yaw, pitch);
        if (team == 1) {
            arena.team1Spawns.add(spawn);
        } else if (team == 2) {
            arena.team2Spawns.add(spawn);
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
            removed = arena.team1Spawns.removeIf(s -> s.pos().equals(pos));
        } else if (team == 2) {
            removed = arena.team2Spawns.removeIf(s -> s.pos().equals(pos));
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

    public static boolean hasBounds(Arena arena) {
        return arena != null && arena.pos1 != null && arena.pos2 != null;
    }

    /** True when both teams have at least {@code teamSize} spawns (1 for 1v1, 2 for 2v2). */
    public static boolean isReadyFor(Arena arena, int teamSize) {
        if (arena == null) return false;
        return arena.team1Spawns.size() >= teamSize && arena.team2Spawns.size() >= teamSize;
    }

    /** Count of spawn points that fall outside the arena's bounding box (0 if bounds unset). */
    public static int spawnsOutsideBounds(Arena arena) {
        if (!hasBounds(arena)) return 0;
        int minX = Math.min(arena.pos1.getX(), arena.pos2.getX());
        int maxX = Math.max(arena.pos1.getX(), arena.pos2.getX());
        int minY = Math.min(arena.pos1.getY(), arena.pos2.getY());
        int maxY = Math.max(arena.pos1.getY(), arena.pos2.getY());
        int minZ = Math.min(arena.pos1.getZ(), arena.pos2.getZ());
        int maxZ = Math.max(arena.pos1.getZ(), arena.pos2.getZ());
        int count = 0;
        for (SpawnPoint s : allSpawns(arena)) {
            if (s.x < minX || s.x > maxX || s.y < minY || s.y > maxY || s.z < minZ || s.z > maxZ) {
                count++;
            }
        }
        return count;
    }

    public static List<SpawnPoint> allSpawns(Arena arena) {
        List<SpawnPoint> all = new ArrayList<>(arena.team1Spawns);
        all.addAll(arena.team2Spawns);
        return all;
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

    public static List<SpawnPoint> pickSpawns(Arena arena, int team, int count) {
        if (arena == null) return List.of();
        List<SpawnPoint> spawns = new ArrayList<>(team == 1 ? arena.team1Spawns : arena.team2Spawns);
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
        if (server == null) {
            return;
        }
        List<Arena> stored = ArenaSavedData.get(server).getArenas();
        if (!stored.isEmpty()) {
            for (Arena a : stored) {
                arenas.put(a.name, a);
            }
            return;
        }
        // One-time migration: import the legacy duel_arenas.json into the SavedData store.
        if (arenasFile != null && arenasFile.exists()) {
            try (FileReader reader = new FileReader(arenasFile)) {
                ArenasFile data = GSON.fromJson(reader, ArenasFile.class);
                if (data != null && data.arenas != null) {
                    for (Arena a : data.arenas) {
                        arenas.put(a.name, a);
                    }
                    LOGGER.info("Imported {} arena(s) from legacy duel_arenas.json.", arenas.size());
                }
            } catch (IOException e) {
                LOGGER.error("Failed to import legacy arenas.", e);
            }
            save();
        }
    }

    private static void save() {
        if (server == null) {
            return;
        }
        ArenaSavedData.get(server).setArenas(new ArrayList<>(arenas.values()));
    }

    public static class Arena {
        public String name;
        public String dimensionId;
        public BlockPos pos1;
        public BlockPos pos2;
        public List<SpawnPoint> team1Spawns = new ArrayList<>();
        public List<SpawnPoint> team2Spawns = new ArrayList<>();

        public Arena() {}

        public Arena(String name, String dimensionId) {
            this.name = name;
            this.dimensionId = dimensionId;
        }

        public static final Codec<Arena> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(a -> a.name),
            Codec.STRING.fieldOf("dimension").forGetter(a -> a.dimensionId),
            BlockPos.CODEC.optionalFieldOf("pos1").forGetter(a -> Optional.ofNullable(a.pos1)),
            BlockPos.CODEC.optionalFieldOf("pos2").forGetter(a -> Optional.ofNullable(a.pos2)),
            SpawnPoint.CODEC.listOf().optionalFieldOf("team1", List.of()).forGetter(a -> a.team1Spawns),
            SpawnPoint.CODEC.listOf().optionalFieldOf("team2", List.of()).forGetter(a -> a.team2Spawns)
        ).apply(i, Arena::fromCodec));

        private static Arena fromCodec(String name, String dimension, Optional<BlockPos> pos1, Optional<BlockPos> pos2,
                                       List<SpawnPoint> team1, List<SpawnPoint> team2) {
            Arena arena = new Arena(name, dimension);
            arena.pos1 = pos1.orElse(null);
            arena.pos2 = pos2.orElse(null);
            arena.team1Spawns = new ArrayList<>(team1);
            arena.team2Spawns = new ArrayList<>(team2);
            return arena;
        }

        public ResourceKey<Level> getDimensionKey() {
            ResourceLocation id = ResourceLocation.tryParse(dimensionId);
            if (id == null) {
                id = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
            }
            return ResourceKey.create(Registries.DIMENSION, id);
        }
    }

    /**
     * A team spawn: block position plus the facing (yaw/pitch) a dueler should look when teleported
     * in. Old arena files stored bare BlockPos ({@code {x,y,z}}); Gson loads those here with
     * yaw/pitch defaulting to 0, so existing arenas stay valid.
     */
    public static class SpawnPoint {
        public int x;
        public int y;
        public int z;
        public float yaw;
        public float pitch;

        public SpawnPoint() {}

        public static final Codec<SpawnPoint> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("x").forGetter(s -> s.x),
            Codec.INT.fieldOf("y").forGetter(s -> s.y),
            Codec.INT.fieldOf("z").forGetter(s -> s.z),
            Codec.FLOAT.optionalFieldOf("yaw", 0.0f).forGetter(s -> s.yaw),
            Codec.FLOAT.optionalFieldOf("pitch", 0.0f).forGetter(s -> s.pitch)
        ).apply(i, (x, y, z, yaw, pitch) -> new SpawnPoint(new BlockPos(x, y, z), yaw, pitch)));

        public SpawnPoint(BlockPos pos, float yaw, float pitch) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        /** Standing position at the block (feet on the block top face). */
        public net.minecraft.world.phys.Vec3 center() {
            return new net.minecraft.world.phys.Vec3(x + 0.5, y, z + 0.5);
        }

        public float yaw() {
            return yaw;
        }

        public float pitch() {
            return pitch;
        }
    }

    private static class ArenasFile {
        public List<Arena> arenas = new ArrayList<>();
    }
}
