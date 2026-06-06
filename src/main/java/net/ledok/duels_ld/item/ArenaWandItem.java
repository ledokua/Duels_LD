package net.ledok.duels_ld.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * The dedicated duel arena selection wand. The click handling and per-arena binding live in
 * {@link net.ledok.duels_ld.manager.ArenaWand}; this class just supplies the item identity,
 * model, and tooltip.
 */
public class ArenaWandItem extends Item {
    public ArenaWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.duels_ld.arena_wand.hint").withStyle(ChatFormatting.GRAY));
    }
}
