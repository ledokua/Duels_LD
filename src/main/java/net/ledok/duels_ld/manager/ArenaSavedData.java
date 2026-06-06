package net.ledok.duels_ld.manager;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the arena definitions as world {@link SavedData} (via {@link ArenaManager.Arena}'s
 * Codec), replacing the hand-rolled {@code duel_arenas.json}. {@link ArenaManager} keeps the live
 * in-memory map and writes through to this store; {@link ArenaManager#load()} imports the legacy
 * JSON once if this store is still empty.
 */
public class ArenaSavedData extends SavedData {
    private static final String DATA_ID = "duels_ld/arenas";
    private static final String KEY = "arenas";

    private static final Factory<ArenaSavedData> FACTORY =
        new Factory<>(ArenaSavedData::new, ArenaSavedData::load, null);

    private List<ArenaManager.Arena> arenas = new ArrayList<>();

    public static ArenaSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    public List<ArenaManager.Arena> getArenas() {
        return arenas;
    }

    public void setArenas(List<ArenaManager.Arena> arenas) {
        this.arenas = new ArrayList<>(arenas);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        ArenaManager.Arena.CODEC.listOf().encodeStart(ops, arenas).result()
            .ifPresent(encoded -> tag.put(KEY, encoded));
        return tag;
    }

    private static ArenaSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ArenaSavedData data = new ArenaSavedData();
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        if (tag.contains(KEY)) {
            ArenaManager.Arena.CODEC.listOf().parse(ops, tag.get(KEY)).result()
                .ifPresent(list -> data.arenas = new ArrayList<>(list));
        }
        return data;
    }
}
