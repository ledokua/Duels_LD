package net.ledok.duels_ld.registry;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.ledok.duels_ld.DuelsLdMod;
import net.ledok.duels_ld.item.ArenaWandItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

public final class ItemRegistry {

    public static final Item ARENA_WAND =
        register(new ArenaWandItem(new Item.Properties().stacksTo(1)), "arena_wand");

    private ItemRegistry() {
    }

    private static Item register(Item item, String id) {
        return Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, id), item);
    }

    /** Forces class load (registering the items) and adds them to a creative tab. */
    public static void initialize() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
            .register(entries -> entries.accept(ARENA_WAND));
    }
}
