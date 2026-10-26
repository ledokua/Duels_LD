package net.ledok.duels_ld.manager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.ledok.duels_ld.api.DuelsApi;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** The last {@value #MAX_PER_PLAYER} matches per player (newest first), as world {@link SavedData}. */
public class MatchHistoryStore extends SavedData {
    public static final int MAX_PER_PLAYER = 20;
    private static final String DATA_ID = "duels_ld/history";
    private static final String KEY = "players";

    private static final Codec<DuelsApi.Mode> MODE_CODEC = Codec.STRING.xmap(
        s -> DuelsApi.Mode.valueOf(s.toUpperCase(Locale.ROOT)), m -> m.name().toLowerCase(Locale.ROOT));
    private static final Codec<DuelsApi.Result> RESULT_CODEC = Codec.STRING.xmap(
        s -> DuelsApi.Result.valueOf(s.toUpperCase(Locale.ROOT)), r -> r.name().toLowerCase(Locale.ROOT));
    private static final Codec<DuelsApi.MatchRecord> RECORD_CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.LONG.fieldOf("ended").forGetter(DuelsApi.MatchRecord::endedAtMillis),
        MODE_CODEC.fieldOf("mode").forGetter(DuelsApi.MatchRecord::mode),
        RESULT_CODEC.fieldOf("result").forGetter(DuelsApi.MatchRecord::result),
        Codec.INT.fieldOf("before").forGetter(DuelsApi.MatchRecord::ratingBefore),
        Codec.INT.fieldOf("after").forGetter(DuelsApi.MatchRecord::ratingAfter),
        Codec.STRING.listOf().optionalFieldOf("team", List.of()).forGetter(DuelsApi.MatchRecord::teammates),
        Codec.STRING.listOf().optionalFieldOf("opponents", List.of()).forGetter(DuelsApi.MatchRecord::opponents),
        Codec.LONG.optionalFieldOf("duration", 0L).forGetter(DuelsApi.MatchRecord::durationMillis)
    ).apply(i, DuelsApi.MatchRecord::new));
    private static final Codec<Map<UUID, List<DuelsApi.MatchRecord>>> CODEC =
        Codec.unboundedMap(UUIDUtil.STRING_CODEC, RECORD_CODEC.listOf());

    private static final Factory<MatchHistoryStore> FACTORY =
        new Factory<>(MatchHistoryStore::new, MatchHistoryStore::load, null);

    private final Map<UUID, List<DuelsApi.MatchRecord>> history = new HashMap<>();

    public static MatchHistoryStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    public void add(UUID player, DuelsApi.MatchRecord record) {
        List<DuelsApi.MatchRecord> list = history.computeIfAbsent(player, k -> new ArrayList<>());
        list.add(0, record);
        while (list.size() > MAX_PER_PLAYER) {
            list.remove(list.size() - 1);
        }
        setDirty();
    }

    /** Newest first; empty (and nothing stored) for a player without history. */
    public List<DuelsApi.MatchRecord> history(UUID player, int limit) {
        List<DuelsApi.MatchRecord> list = history.get(player);
        if (list == null || limit <= 0) {
            return List.of();
        }
        return List.copyOf(list.subList(0, Math.min(limit, list.size())));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        CODEC.encodeStart(ops, history).result().ifPresent(encoded -> tag.put(KEY, encoded));
        return tag;
    }

    private static MatchHistoryStore load(CompoundTag tag, HolderLookup.Provider registries) {
        MatchHistoryStore store = new MatchHistoryStore();
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        if (tag.contains(KEY)) {
            CODEC.parse(ops, tag.get(KEY)).result().ifPresent(parsed ->
                parsed.forEach((id, list) -> store.history.put(id, new ArrayList<>(list))));
        }
        return store;
    }
}
