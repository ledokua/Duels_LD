package net.ledok.duels_ld.wager;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class Wager {
    public enum Kind { NONE, CURRENCY, ITEM }

    private final Kind kind;
    private final long currencyAmount;
    private final ItemStack itemTemplate;
    private final int itemCount;

    private Wager(Kind kind, long currencyAmount, ItemStack itemTemplate, int itemCount) {
        this.kind = kind;
        this.currencyAmount = currencyAmount;
        this.itemTemplate = itemTemplate;
        this.itemCount = itemCount;
    }

    public static Wager none() {
        return new Wager(Kind.NONE, 0L, ItemStack.EMPTY, 0);
    }

    public static Wager currency(long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("currency wager must be > 0");
        }
        return new Wager(Kind.CURRENCY, amount, ItemStack.EMPTY, 0);
    }

    public static Wager item(ItemStack template, int count) {
        if (template == null || template.isEmpty() || count <= 0) {
            throw new IllegalArgumentException("item wager requires non-empty stack");
        }
        return new Wager(Kind.ITEM, 0L, template.copyWithCount(1), count);
    }

    public Kind getKind() { return kind; }
    public long getCurrencyAmount() { return currencyAmount; }
    public ItemStack getItemTemplate() { return itemTemplate; }
    public int getItemCount() { return itemCount; }
    public boolean isNone() { return kind == Kind.NONE; }

    public Component describe() {
        return switch (kind) {
            case NONE -> Component.translatable("duels_ld.wager.none");
            case CURRENCY -> Component.translatable("duels_ld.wager.currency", currencyAmount);
            case ITEM -> Component.translatable("duels_ld.wager.item", itemCount, itemTemplate.getHoverName());
        };
    }
}
