package net.ledok.duels_ld.manager;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent, server-global record of where to restore each player after a duel, keyed by UUID.
 *
 * <p>Stored as world {@link SavedData} on the overworld so a backup survives a server restart and
 * can be looked up on login regardless of which dimension the player is in. This is what lets a
 * player who was mid-duel when the server stopped be returned to their pre-duel location instead
 * of being stranded in the arena/PvP dimension.
 */
public class DuelBackupStore extends SavedData {
    private static final String DATA_ID = "duels_ld_backups";
    private static final String KEY_LIST = "backups";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_BACKUP = "backup";

    private static final Factory<DuelBackupStore> FACTORY =
        new Factory<>(DuelBackupStore::new, DuelBackupStore::load, null);

    private final Map<UUID, PlayerBackup> backups = new HashMap<>();

    public static DuelBackupStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    public void put(UUID uuid, PlayerBackup backup) {
        backups.put(uuid, backup);
        setDirty();
    }

    public void putIfAbsent(UUID uuid, PlayerBackup backup) {
        if (backups.putIfAbsent(uuid, backup) == null) {
            setDirty();
        }
    }

    public boolean contains(UUID uuid) {
        return backups.containsKey(uuid);
    }

    /** Returns and removes the backup for a player, or {@code null} if none. */
    @Nullable
    public PlayerBackup take(UUID uuid) {
        PlayerBackup removed = backups.remove(uuid);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        ListTag list = new ListTag();
        for (Map.Entry<UUID, PlayerBackup> entry : backups.entrySet()) {
            CompoundTag element = new CompoundTag();
            element.putUUID(KEY_UUID, entry.getKey());
            PlayerBackup.CODEC.encodeStart(ops, entry.getValue()).result()
                .ifPresent(encoded -> element.put(KEY_BACKUP, encoded));
            list.add(element);
        }
        tag.put(KEY_LIST, list);
        return tag;
    }

    private static DuelBackupStore load(CompoundTag tag, HolderLookup.Provider registries) {
        DuelBackupStore store = new DuelBackupStore();
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        ListTag list = tag.getList(KEY_LIST, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag element = list.getCompound(i);
            if (!element.hasUUID(KEY_UUID) || !element.contains(KEY_BACKUP)) {
                continue;
            }
            UUID uuid = element.getUUID(KEY_UUID);
            PlayerBackup.CODEC.parse(ops, element.get(KEY_BACKUP)).result()
                .ifPresent(backup -> store.backups.put(uuid, backup));
        }
        return store;
    }
}
