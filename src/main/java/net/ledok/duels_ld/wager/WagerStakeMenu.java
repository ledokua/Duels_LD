package net.ledok.duels_ld.wager;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.ledok.duels_ld.DuelsLdMod;
import net.ledok.duels_ld.api.WagerResult;
import net.ledok.duels_ld.network.WagerStatusPayload;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The stake screen's server menu: {@link Data#slots} stake slots (indexes 0..n-1) followed by the
 * player's inventory (27 main + 9 hotbar). Used both to put up a listing ({@link Data#MODE_CREATE})
 * and to offer on one ({@link Data#MODE_OFFER}).
 *
 * <p>The stake slots are the {@link StakeSource}: the duel bank takes the items only after it has
 * written the stake to disk. Until then they are the player's, and closing the screen gives them
 * back like a crafting grid does. While coins are being taken the slots are locked, so the stake
 * can't change under the bank.
 */
public class WagerStakeMenu extends AbstractContainerMenu implements StakeSource {

    public static MenuType<WagerStakeMenu> TYPE;

    /**
     * Everything the client screen shows, sent once when it opens.
     *
     * @param balance       the player's coins when the screen opened (display and the client-side check only)
     * @param fixedCoins    on an offer to a listing that has coins: the coins the offer must match, else -1
     * @param authorItems   on an offer: the author's staked items (shown with full tooltips)
     * @param remainingMillis on an offer: time left on the listing
     */
    public record Data(int mode, int number, String targetName, long balance, boolean coinsEnabled, int slots,
                       long minCoins, int minItems, long maxCoins, int listingMinutes, int fightMinutes,
                       int commentMax, List<String> bannedItems, boolean skipLossWarning,
                       String authorName, List<ItemStack> authorItems, long authorCoins, String authorComment,
                       long fixedCoins, long remainingMillis) {
        public static final int MODE_CREATE = 0;
        public static final int MODE_OFFER = 1;

        public static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.of((buf, d) -> {
            buf.writeVarInt(d.mode);
            buf.writeVarInt(d.number);
            buf.writeUtf(d.targetName);
            buf.writeVarLong(d.balance);
            buf.writeBoolean(d.coinsEnabled);
            buf.writeVarInt(d.slots);
            buf.writeVarLong(d.minCoins);
            buf.writeVarInt(d.minItems);
            buf.writeVarLong(d.maxCoins);
            buf.writeVarInt(d.listingMinutes);
            buf.writeVarInt(d.fightMinutes);
            buf.writeVarInt(d.commentMax);
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, d.bannedItems);
            buf.writeBoolean(d.skipLossWarning);
            buf.writeUtf(d.authorName);
            ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, d.authorItems);
            buf.writeVarLong(d.authorCoins);
            buf.writeUtf(d.authorComment);
            buf.writeLong(d.fixedCoins);
            buf.writeVarLong(d.remainingMillis);
        }, buf -> new Data(buf.readVarInt(), buf.readVarInt(), buf.readUtf(), buf.readVarLong(), buf.readBoolean(),
            buf.readVarInt(), buf.readVarLong(), buf.readVarInt(), buf.readVarLong(), buf.readVarInt(), buf.readVarInt(),
            buf.readVarInt(), ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf), buf.readBoolean(),
            buf.readUtf(), ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buf), buf.readVarLong(), buf.readUtf(),
            buf.readLong(), buf.readVarLong()));

        public boolean offer() {
            return mode == MODE_OFFER;
        }
    }

    public static void register() {
        TYPE = Registry.register(BuiltInRegistries.MENU,
            ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "wager_stake"),
            new ExtendedScreenHandlerType<>(WagerStakeMenu::new, Data.CODEC));
    }

    private final Data data;
    private final Container stake;
    private final Player player;
    /** Set while coins are being taken: nothing may move in or out of the stake slots. */
    private boolean locked;

    public WagerStakeMenu(int containerId, Inventory inventory, Data data) {
        super(TYPE, containerId);
        this.data = data;
        this.player = inventory.player;
        this.stake = new SimpleContainer(Math.max(1, data.slots));
        for (int i = 0; i < stake.getContainerSize(); i++) {
            addSlot(new StakeSlot(stake, i));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 0, 0));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 0, 0));
        }
    }

    public Data data() {
        return data;
    }

    public int stakeSlots() {
        return stake.getContainerSize();
    }

    public boolean locked() {
        return locked;
    }

    /** Whether the item may go into a stake slot (shown red on the client otherwise). */
    public boolean allowed(ItemStack stack) {
        return WagerItems.check(stack, player.registryAccess(), data.bannedItems) == null;
    }

    private final class StakeSlot extends Slot {
        StakeSlot(Container container, int index) {
            super(container, index, 0, 0);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !locked && allowed(stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return !locked;
        }
    }

    // ── Player actions (server) ──────────────────────────────────────────────

    /** "Post listing" / "Send challenge" / "Offer stake" from the client. */
    public void submit(ServerPlayer sender, long coins, String comment) {
        if (locked) {
            return;
        }
        locked = true;
        WagerResult result;
        if (data.offer()) {
            result = WagerManager.makeOffer(sender, data.number, this, coins);
        } else {
            ServerPlayer target = data.targetName.isEmpty() ? null
                : sender.server.getPlayerList().getPlayerByName(data.targetName);
            if (!data.targetName.isEmpty() && target == null) {
                result = WagerResult.TARGET_OFFLINE;
            } else {
                result = WagerManager.createListing(sender, this, coins, comment, target);
            }
        }
        if (result != WagerResult.PENDING) {
            finished(result);
        }
    }

    @Override
    public void finished(WagerResult result) {
        locked = false;
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        if (result == WagerResult.OK) {
            if (sp.containerMenu == this) {
                sp.closeContainer();
            }
        } else {
            ServerPlayNetworking.send(sp, new WagerStatusPayload(containerId, false, result.message()));
        }
    }

    // ── StakeSource ──────────────────────────────────────────────────────────

    @Override
    public List<ItemStack> items() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < stake.getContainerSize(); i++) {
            out.add(stake.getItem(i).copy());
        }
        return out;
    }

    @Override
    public void clear() {
        stake.clearContent();
        broadcastChanges();
    }

    // ── Container plumbing ───────────────────────────────────────────────────

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (locked) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int stakeEnd = stake.getContainerSize();
        if (index < stakeEnd) {
            if (!moveItemStackTo(stack, stakeEnd, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!allowed(stack) || !moveItemStackTo(stack, 0, stakeEnd, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive();
    }

    /** Closing without staking gives the items back (to the inventory, or dropped when it's full). */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide) {
            clearContainer(player, stake);
        }
    }
}
