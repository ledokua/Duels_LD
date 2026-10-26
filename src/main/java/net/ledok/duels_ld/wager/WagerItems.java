package net.ledok.duels_ld.wager;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Which stacks may go into a stake. A stake is shown slot by slot, so anything whose real content
 * is hidden inside it — a filled shulker box, bundle or modded backpack, a loaded crossbow, a chest
 * with loot still to roll — is refused, plus the {@code duels_ld:wager_banned} tag and the config list.
 */
public final class WagerItems {
    public static final TagKey<Item> BANNED_TAG =
        TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("duels_ld", "wager_banned"));

    public enum Refusal {
        /** Holds other items (or a loot table) you can't see in the slot. */
        CONTAINER,
        /** On the server's banned list or tag. */
        BANNED
    }

    private WagerItems() {
    }

    /** Why the stack can't be staked, or null when it can. */
    @Nullable
    public static Refusal check(ItemStack stack, HolderLookup.Provider registries) {
        return check(stack, registries, WagerConfig.get().bannedItems);
    }

    /** As {@link #check(ItemStack, HolderLookup.Provider)} with an explicit banned list (the client gets the server's). */
    @Nullable
    public static Refusal check(ItemStack stack, HolderLookup.Provider registries, java.util.List<String> bannedItems) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stack.is(BANNED_TAG)
            || bannedItems.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
            return Refusal.BANNED;
        }
        if (stack.has(DataComponents.CONTAINER_LOOT)) {
            return Refusal.CONTAINER;
        }
        // Generic, so modded backpacks and bags count too: any component that serializes to
        // something holding an item stack (an "id" plus a count) hides items in the slot.
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        for (TypedDataComponent<?> component : stack.getComponents()) {
            Tag encoded = component.encodeValue(ops).result().orElse(null);
            if (encoded != null && holdsItem(encoded, 0)) {
                return Refusal.CONTAINER;
            }
        }
        return null;
    }

    private static boolean holdsItem(Tag tag, int depth) {
        if (depth > 12) {
            return false;
        }
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("id", Tag.TAG_STRING)
                && (compound.contains("count", Tag.TAG_ANY_NUMERIC) || compound.contains("Count", Tag.TAG_ANY_NUMERIC))) {
                return true;
            }
            for (String key : compound.getAllKeys()) {
                Tag child = compound.get(key);
                if (child != null && holdsItem(child, depth + 1)) {
                    return true;
                }
            }
        } else if (tag instanceof ListTag list) {
            for (Tag child : list) {
                if (holdsItem(child, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }
}
