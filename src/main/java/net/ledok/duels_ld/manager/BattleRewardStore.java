package net.ledok.duels_ld.manager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persists per-player battle-reward limit counters (daily earnings + per-opponent match counts)
 * as world {@link SavedData}. Counters reset when the UTC day rolls over.
 */
public class BattleRewardStore extends SavedData {
    private static final String DATA_ID = "duels_ld/rewards";
    private static final String KEY = "players";

    private static final Factory<BattleRewardStore> FACTORY =
        new Factory<>(BattleRewardStore::new, BattleRewardStore::load, null);

    private final Map<UUID, Entry> entries = new HashMap<>();

    public static BattleRewardStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    public static long today() {
        return System.currentTimeMillis() / 86_400_000L;
    }

    /** Returns the player's entry for today, resetting it if a new day has started. */
    private Entry current(UUID uuid, long today) {
        Entry e = entries.computeIfAbsent(uuid, k -> new Entry(today, 0L, new HashMap<>()));
        if (e.dayKey != today) {
            e.dayKey = today;
            e.earnedToday = 0L;
            e.opponents.clear();
            setDirty();
        }
        return e;
    }

    public long earnedToday(UUID uuid, long today) {
        return current(uuid, today).earnedToday;
    }

    /** Highest prior match count this player has against any of the given opponents today. */
    public int maxOpponentCount(UUID uuid, Collection<UUID> opponents, long today) {
        Entry e = current(uuid, today);
        int max = 0;
        for (UUID opp : opponents) {
            max = Math.max(max, e.opponents.getOrDefault(opp, 0));
        }
        return max;
    }

    /** Records a paid reward: adds to the daily total and bumps each opponent's match count. */
    public void record(UUID uuid, long amount, Collection<UUID> opponents, long today) {
        Entry e = current(uuid, today);
        e.earnedToday += amount;
        for (UUID opp : opponents) {
            e.opponents.merge(opp, 1, Integer::sum);
        }
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC).encodeStart(ops, entries).result()
            .ifPresent(encoded -> tag.put(KEY, encoded));
        return tag;
    }

    private static BattleRewardStore load(CompoundTag tag, HolderLookup.Provider registries) {
        BattleRewardStore store = new BattleRewardStore();
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        if (tag.contains(KEY)) {
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC).parse(ops, tag.get(KEY)).result()
                .ifPresent(store.entries::putAll);
        }
        return store;
    }

    static final class Entry {
        long dayKey;
        long earnedToday;
        final Map<UUID, Integer> opponents;

        Entry(long dayKey, long earnedToday, Map<UUID, Integer> opponents) {
            this.dayKey = dayKey;
            this.earnedToday = earnedToday;
            this.opponents = new HashMap<>(opponents);
        }

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("day").forGetter(e -> e.dayKey),
            Codec.LONG.fieldOf("earned").forGetter(e -> e.earnedToday),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT).optionalFieldOf("opp", Map.of()).forGetter(e -> e.opponents)
        ).apply(i, Entry::new));
    }
}
