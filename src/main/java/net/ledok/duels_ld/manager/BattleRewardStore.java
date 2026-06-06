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
import java.util.List;
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
        Entry e = entries.computeIfAbsent(uuid, k -> new Entry(today, new long[3], new HashMap<>()));
        if (e.dayKey != today) {
            e.dayKey = today;
            java.util.Arrays.fill(e.earned, 0L);
            e.opponents.clear();
            setDirty();
        }
        return e;
    }

    /** Coins earned today for one outcome (0 = win, 1 = loss, 2 = draw). */
    public long earned(UUID uuid, int outcome, long today) {
        return current(uuid, today).earned[outcome];
    }

    /** Total coins earned today across all outcomes (for the screen display). */
    public long totalEarned(UUID uuid, long today) {
        long[] earned = current(uuid, today).earned;
        return earned[0] + earned[1] + earned[2];
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

    /** Records a paid reward for an outcome: adds to that day-total and bumps opponent counts. */
    public void record(UUID uuid, int outcome, long amount, Collection<UUID> opponents, long today) {
        Entry e = current(uuid, today);
        e.earned[outcome] += amount;
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
        final long[] earned; // [win, loss, draw]
        final Map<UUID, Integer> opponents;

        Entry(long dayKey, long[] earned, Map<UUID, Integer> opponents) {
            this.dayKey = dayKey;
            this.earned = normalize(earned);
            this.opponents = new HashMap<>(opponents);
        }

        private static long[] normalize(long[] earned) {
            long[] out = new long[3];
            if (earned != null) {
                System.arraycopy(earned, 0, out, 0, Math.min(3, earned.length));
            }
            return out;
        }

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("day").forGetter(e -> e.dayKey),
            Codec.LONG.listOf().optionalFieldOf("earned", List.of()).forGetter(e -> List.of(e.earned[0], e.earned[1], e.earned[2])),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT).optionalFieldOf("opp", Map.of()).forGetter(e -> e.opponents)
        ).apply(i, (day, earnedList, opp) -> new Entry(day, earnedList.stream().mapToLong(Long::longValue).toArray(), opp)));
    }
}
