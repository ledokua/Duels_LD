package net.ledok.duels_ld.manager;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Keeps arenas empty between fights. Spell summons, projectiles, dropped items and the like used
 * to stay in the arena when a fight ended (the chunks unloaded with them saved inside), and came
 * back in the next fight there — on top of new ones.
 *
 * <p>An arena is swept when a fight starts (after the teleport), again when the countdown ends
 * (entities load a few ticks after their chunk), and when the fight ends, before anyone is
 * teleported out, while the arena is still loaded. A sweep removes every entity inside the arena
 * bounds except players, fixtures (armor stands, item frames, paintings, leash knots, display /
 * marker / interaction entities, NPCs_LD NPCs) and owned entities. Removed are projectiles,
 * items, XP orbs, clouds, TNT, falling blocks and unowned mobs.
 *
 * <p>Owned entities are never deleted at the end of a fight: pets and Spell Engine summons alike
 * are teleported to their owner once the players are out (a Saint's Dragons binder empties itself
 * on summon, so deleting a dragon would lose it for good; spell summons expire on their own timer).
 * An offline owner's entities stay where they are. At the start of a fight, Spell Engine summons
 * that loaded from disk are removed — usually a fighter's leftover from an earlier fight, which
 * returning to its owner would put straight back in the arena — while real pets are kept.
 * Anything else freshly spawned in an arena while its fight runs (not loaded from disk), fixtures
 * included, is removed at the end. Each sweep is logged with counts by type, for moderators.
 */
public final class ArenaCleanup {
    private static final String SPELL_SUMMON_CLASS = "net.spell_engine.entity.SummonedEntity";

    /** Arena name → entities that appeared inside it during the running fight. */
    private static final Map<String, Set<UUID>> spawnedDuringFight = new HashMap<>();

    private ArenaCleanup() {
    }

    /** A fresh entity was added to a level (ServerLevelFreshEntityMixin): track it if it's inside a fighting arena. */
    public static void onFreshEntity(ServerLevel level, Entity entity) {
        if (entity instanceof Player || spawnedDuringFight.isEmpty()) {
            return;
        }
        for (ArenaManager.Arena arena : ArenaManager.listArenas()) {
            Set<UUID> tracked = spawnedDuringFight.get(arena.name);
            if (tracked != null && level.dimension().equals(arena.getDimensionKey()) && contains(arena, entity)) {
                tracked.add(entity.getUUID());
                return;
            }
        }
    }

    /** The fight in this arena starts: sweep it, and begin tracking what appears inside. */
    public static void onFightStart(MinecraftServer server, @Nullable String arenaName, String stage, @Nullable String duelLabel) {
        ArenaManager.Arena arena = arena(arenaName);
        if (arena == null) {
            return;
        }
        spawnedDuringFight.computeIfAbsent(arena.name, k -> new HashSet<>());
        sweep(server, arena, stage, duelLabel, Set.of(), false);
    }

    /**
     * The fight in this arena ended: remove what it left behind (call before teleporting players
     * out). Returns the pets found inside, for {@link #returnPets} once the players are out.
     */
    public static List<Entity> onFightEnd(MinecraftServer server, @Nullable String arenaName, @Nullable String duelLabel) {
        ArenaManager.Arena arena = arena(arenaName);
        if (arena == null) {
            return List.of();
        }
        Set<UUID> tracked = spawnedDuringFight.remove(arena.name);
        return sweep(server, arena, "end", duelLabel, tracked == null ? Set.of() : tracked, true);
    }

    /** Teleports owned entities left in an arena to their owners (call after the players were teleported out). */
    public static void returnPets(MinecraftServer server, List<Entity> pets) {
        for (Entity pet : pets) {
            if (pet.isRemoved() || !(pet instanceof OwnableEntity ownable) || ownable.getOwnerUUID() == null) {
                continue;
            }
            net.minecraft.server.level.ServerPlayer owner = server.getPlayerList().getPlayer(ownable.getOwnerUUID());
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()).toString();
            if (owner == null) {
                DuelsLdMod.LOGGER.info("[Duels] {} ({}) stays in the arena: its owner is offline.", type, pet.getUUID());
                continue;
            }
            boolean moved = pet.teleportTo(owner.serverLevel(), owner.getX(), owner.getY(), owner.getZ(),
                java.util.Set.of(), pet.getYRot(), pet.getXRot());
            DuelsLdMod.LOGGER.info("[Duels] {} returned to {}{}.", type, owner.getGameProfile().getName(),
                moved ? "" : " (teleport failed, it stays in the arena)");
        }
    }

    private static List<Entity> sweep(MinecraftServer server, ArenaManager.Arena arena, String stage, @Nullable String duelLabel,
                                      Set<UUID> spawnedDuringFight, boolean endOfFight) {
        List<Entity> pets = new ArrayList<>();
        if (!ArenaManager.hasBounds(arena)) {
            return pets;
        }
        ServerLevel level = server.getLevel(arena.getDimensionKey());
        if (level == null) {
            return pets;
        }
        List<Entity> found = level.getEntities((Entity) null, bounds(arena), entity -> !(entity instanceof Player));
        Map<String, Integer> removed = new TreeMap<>();
        int keptPets = 0;
        for (Entity entity : new ArrayList<>(found)) {
            if (entity.isRemoved() || entity.isPassenger() && entity.getRootVehicle() instanceof Player) {
                continue;
            }
            // End: everything owned goes back to its owner. Start: spell summons loaded from disk go.
            if (endOfFight ? isOwned(entity) : isPet(entity)) {
                pets.add(entity);
                keptPets++;
                continue;
            }
            boolean spawnedNow = spawnedDuringFight.contains(entity.getUUID());
            if (!spawnedNow && isFixture(entity)) {
                continue;
            }
            removed.merge(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), 1, Integer::sum);
            entity.ejectPassengers();
            entity.discard();
        }
        if (!removed.isEmpty() || keptPets > 0) {
            DuelsLdMod.LOGGER.info("[Duels] Arena {} cleanup ({}{}): removed {}{}", arena.name, stage,
                duelLabel == null ? "" : ", " + duelLabel, removed.isEmpty() ? "nothing" : removed,
                keptPets > 0 ? ", kept " + keptPets + (endOfFight ? " owned (returning to owners)" : " pet(s)") : "");
        }
        return pets;
    }

    /** Things that belong to the arena build. */
    private static boolean isFixture(Entity entity) {
        if (entity instanceof ArmorStand || entity instanceof BlockAttachedEntity || entity instanceof Display
            || entity instanceof Marker || entity instanceof Interaction) {
            return true;
        }
        return "npcs_ld".equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace());
    }

    private static boolean isOwned(Entity entity) {
        return entity instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null;
    }

    /** A player's own pet (tamed wolf, dragon, horse…), as opposed to a Spell Engine summon, which is owned too. */
    private static boolean isPet(Entity entity) {
        return entity instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null && !isSpellSummon(entity);
    }

    private static boolean isSpellSummon(Entity entity) {
        for (Class<?> c = entity.getClass(); c != null; c = c.getSuperclass()) {
            if (SPELL_SUMMON_CLASS.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(ArenaManager.Arena arena, Entity entity) {
        return ArenaManager.hasBounds(arena) && bounds(arena).contains(entity.position());
    }

    private static AABB bounds(ArenaManager.Arena arena) {
        return new AABB(
            Math.min(arena.pos1.getX(), arena.pos2.getX()), Math.min(arena.pos1.getY(), arena.pos2.getY()),
            Math.min(arena.pos1.getZ(), arena.pos2.getZ()),
            Math.max(arena.pos1.getX(), arena.pos2.getX()) + 1, Math.max(arena.pos1.getY(), arena.pos2.getY()) + 1,
            Math.max(arena.pos1.getZ(), arena.pos2.getZ()) + 1);
    }

    @Nullable
    private static ArenaManager.Arena arena(@Nullable String name) {
        return name == null ? null : ArenaManager.getArena(name);
    }
}
