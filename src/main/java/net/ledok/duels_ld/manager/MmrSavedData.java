package net.ledok.duels_ld.manager;

import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Persists 1v1 and 2v2 MMR/Elo ratings as world {@link SavedData}. */
public class MmrSavedData extends SavedData {
    private static final String DATA_ID = "duels_ld/mmr";
    private static final Codec<Map<UUID, Integer>> MAP_CODEC =
        Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT);

    private static final Factory<MmrSavedData> FACTORY =
        new Factory<>(MmrSavedData::new, MmrSavedData::load, null);

    private final Map<UUID, Integer> mmr1v1 = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> mmr2v2 = new ConcurrentHashMap<>();

    public static MmrSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    public Map<UUID, Integer> map1v1() {
        return mmr1v1;
    }

    public Map<UUID, Integer> map2v2() {
        return mmr2v2;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        MAP_CODEC.encodeStart(ops, mmr1v1).result().ifPresent(t -> tag.put("mmr1v1", t));
        MAP_CODEC.encodeStart(ops, mmr2v2).result().ifPresent(t -> tag.put("mmr2v2", t));
        return tag;
    }

    private static MmrSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        MmrSavedData data = new MmrSavedData();
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        if (tag.contains("mmr1v1")) {
            MAP_CODEC.parse(ops, tag.get("mmr1v1")).result().ifPresent(data.mmr1v1::putAll);
        }
        if (tag.contains("mmr2v2")) {
            MAP_CODEC.parse(ops, tag.get("mmr2v2")).result().ifPresent(data.mmr2v2::putAll);
        }
        return data;
    }
}
