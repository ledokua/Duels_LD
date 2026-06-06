package net.ledok.duels_ld.manager;

import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Persists per-player win/loss/draw records as world {@link SavedData}. */
public class StatsSavedData extends SavedData {
    private static final String DATA_ID = "duels_ld_stats";
    private static final String KEY = "stats";
    private static final Codec<Map<UUID, PlayerStats>> CODEC =
        Codec.unboundedMap(UUIDUtil.STRING_CODEC, PlayerStats.CODEC);

    private static final Factory<StatsSavedData> FACTORY =
        new Factory<>(StatsSavedData::new, StatsSavedData::load, null);

    private final Map<UUID, PlayerStats> stats = new ConcurrentHashMap<>();

    public static StatsSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    /** The live map — mutated in place by StatsManager, which calls {@link #setDirty()} on change. */
    public Map<UUID, PlayerStats> stats() {
        return stats;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stats).result()
            .ifPresent(encoded -> tag.put(KEY, encoded));
        return tag;
    }

    private static StatsSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        StatsSavedData data = new StatsSavedData();
        if (tag.contains(KEY)) {
            CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag.get(KEY)).result()
                .ifPresent(data.stats::putAll);
        }
        return data;
    }
}
