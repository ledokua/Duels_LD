package net.ledok.duels_ld.manager;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the configured arena item blacklist (see {@code battle.arenaItemBlacklist}) into item
 * ids and item tags, and tests stacks against it. Entries are plain item ids (e.g.
 * {@code minecraft:ender_pearl}) or tag references prefixed with {@code #} (e.g.
 * {@code #minecraft:beds}).
 *
 * <p>The parsed form is cached and rebuilt only when the config's backing list instance changes,
 * which happens on {@code /duel reload} (a reload swaps in a fresh config object).
 */
public final class ArenaItemRules {
    private ArenaItemRules() {}

    private static List<String> cachedSource;
    private static Set<ResourceLocation> blacklistedIds = Set.of();
    private static List<TagKey<Item>> blacklistedTags = List.of();

    public static boolean isBlacklisted(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        rebuildIfNeeded();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (blacklistedIds.contains(id)) {
            return true;
        }
        for (TagKey<Item> tag : blacklistedTags) {
            if (stack.is(tag)) {
                return true;
            }
        }
        return false;
    }

    private static void rebuildIfNeeded() {
        List<String> source = MatchmakingConfigManager.getConfig().battle.arenaItemBlacklist;
        if (source == cachedSource) {
            return;
        }
        cachedSource = source;

        Set<ResourceLocation> ids = new HashSet<>();
        List<TagKey<Item>> tags = new ArrayList<>();
        if (source != null) {
            for (String raw : source) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String entry = raw.trim();
                if (entry.startsWith("#")) {
                    ResourceLocation tagId = ResourceLocation.tryParse(entry.substring(1));
                    if (tagId == null) {
                        DuelsLdMod.LOGGER.warn("[Duels] Ignoring invalid item tag in blacklist: {}", entry);
                        continue;
                    }
                    tags.add(TagKey.create(Registries.ITEM, tagId));
                } else {
                    ResourceLocation itemId = ResourceLocation.tryParse(entry);
                    if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
                        DuelsLdMod.LOGGER.warn("[Duels] Ignoring unknown item in blacklist: {}", entry);
                        continue;
                    }
                    ids.add(itemId);
                }
            }
        }
        blacklistedIds = ids;
        blacklistedTags = tags;
    }
}
