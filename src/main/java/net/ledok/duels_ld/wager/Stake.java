package net.ledok.duels_ld.wager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What one player puts at risk: item stacks and Economy_LD coins. Immutable; the stacks are copies
 * that nobody else holds, so a stake in the duel bank can't change under it.
 */
public record Stake(List<ItemStack> items, long coins) {
    public static final Stake EMPTY = new Stake(List.of(), 0);

    public static final Codec<Stake> CODEC = RecordCodecBuilder.create(i -> i.group(
        ItemStack.CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(Stake::items),
        Codec.LONG.optionalFieldOf("coins", 0L).forGetter(Stake::coins)
    ).apply(i, Stake::new));

    public Stake {
        List<ItemStack> copies = new ArrayList<>();
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                copies.add(stack.copy());
            }
        }
        items = List.copyOf(copies);
        coins = Math.max(0, coins);
    }

    public boolean isEmpty() {
        return items.isEmpty() && coins <= 0;
    }

    public boolean hasItems() {
        return !items.isEmpty();
    }

    /** Fresh copies of the stacks, safe to hand out (to an inventory or a mail). */
    public List<ItemStack> copyItems() {
        List<ItemStack> out = new ArrayList<>(items.size());
        for (ItemStack stack : items) {
            out.add(stack.copy());
        }
        return out;
    }

    /** Both stakes together: the winner's bank. */
    public Stake plus(Stake other) {
        List<ItemStack> all = new ArrayList<>(items);
        all.addAll(other.items);
        return new Stake(all, coins + other.coins);
    }

    /** "500 coins + 3 items" style summary for chat, logs and mail. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (coins > 0) {
            sb.append(coins).append(" coins");
        }
        for (ItemStack stack : items) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(stack.getCount()).append("x ").append(stack.getHoverName().getString());
        }
        return sb.isEmpty() ? "nothing" : sb.toString();
    }
}
