package net.ledok.duels_ld.manager;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the configured arena effect blacklist (see {@code battle.arenaEffectBlacklist}) into
 * mob-effect holders. Entries are effect ids (e.g. {@code minecraft:speed} or {@code galosphere:astral}).
 *
 * <p>Like {@link ArenaItemRules}, the parsed form is cached and rebuilt only when the config's
 * backing list instance changes (which happens on {@code /duel reload}).
 */
public final class ArenaEffectRules {
    private ArenaEffectRules() {}

    private static List<String> cachedSource;
    private static List<Holder<MobEffect>> blacklisted = List.of();

    /** Mob-effect holders that should be stripped from players during an arena match. */
    public static List<Holder<MobEffect>> blacklistedEffects() {
        rebuildIfNeeded();
        return blacklisted;
    }

    private static void rebuildIfNeeded() {
        List<String> source = MatchmakingConfigManager.getConfig().battle.arenaEffectBlacklist;
        if (source == cachedSource) {
            return;
        }
        cachedSource = source;

        List<Holder<MobEffect>> resolved = new ArrayList<>();
        if (source != null) {
            for (String raw : source) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                ResourceLocation id = ResourceLocation.tryParse(raw.trim());
                if (id == null) {
                    DuelsLdMod.LOGGER.warn("[Duels] Ignoring invalid effect id in blacklist: {}", raw);
                    continue;
                }
                ResourceKey<MobEffect> key = ResourceKey.create(Registries.MOB_EFFECT, id);
                BuiltInRegistries.MOB_EFFECT.getHolder(key).ifPresentOrElse(
                    resolved::add,
                    () -> DuelsLdMod.LOGGER.warn("[Duels] Ignoring unknown effect in blacklist: {}", id));
            }
        }
        blacklisted = List.copyOf(resolved);
    }
}
