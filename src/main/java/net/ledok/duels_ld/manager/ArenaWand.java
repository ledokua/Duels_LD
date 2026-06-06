package net.ledok.duels_ld.manager;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.duels_ld.network.ArenaListPayload;
import net.ledok.duels_ld.network.ArenaOverlayPayload;
import net.ledok.duels_ld.network.ArenaWandModePayload;
import net.ledok.duels_ld.registry.ItemRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;

/**
 * The duel arena selection wand. Bound to an arena (picked via shift+right-click → screen) and has
 * two scroll-cycled modes (shift+scroll):
 * <ul>
 *   <li>BOUNDS — left-click sets pos1, right-click sets pos2.</li>
 *   <li>SPAWNS — right-click toggles a team-1 spawn, left-click toggles a team-2 spawn
 *       (clicking the same spot again removes it).</li>
 * </ul>
 * Binding and mode live in the item's custom data.
 */
public final class ArenaWand {
    private static final String TAG_KEY = "DuelArenaWand";
    private static final String MODE_KEY = "DuelArenaWandMode";
    public static final int MODE_BOUNDS = 0;
    public static final int MODE_SPAWNS = 1;
    private static final int MODE_COUNT = 2;

    private ArenaWand() {
    }

    public static void register() {
        // Left-click a block.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp) || !sp.hasPermissions(2)) {
                return InteractionResult.PASS;
            }
            if (!sp.getItemInHand(hand).is(ItemRegistry.ARENA_WAND)) {
                return InteractionResult.PASS;
            }
            handleClick(sp, sp.getItemInHand(hand), pos, direction, true);
            return InteractionResult.SUCCESS;
        });

        // Right-click a block.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp) || !sp.hasPermissions(2)) {
                return InteractionResult.PASS;
            }
            if (!sp.getItemInHand(hand).is(ItemRegistry.ARENA_WAND)) {
                return InteractionResult.PASS;
            }
            if (sp.isShiftKeyDown()) {
                openWandScreen(sp);
                return InteractionResult.SUCCESS;
            }
            handleClick(sp, sp.getItemInHand(hand), hitResult.getBlockPos(), hitResult.getDirection(), false);
            return InteractionResult.SUCCESS;
        });

        // Shift+right-click in the air also opens the selection screen.
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!world.isClientSide() && player instanceof ServerPlayer sp && sp.hasPermissions(2)
                && stack.is(ItemRegistry.ARENA_WAND) && sp.isShiftKeyDown()) {
                openWandScreen(sp);
                return InteractionResultHolder.success(stack);
            }
            return InteractionResultHolder.pass(stack);
        });

        // Shift+scroll cycles the mode.
        ServerPlayNetworking.registerGlobalReceiver(ArenaWandModePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            player.server.execute(() -> {
                if (!player.hasPermissions(2)) {
                    return;
                }
                ItemStack stack = player.getMainHandItem();
                if (!stack.is(ItemRegistry.ARENA_WAND)) {
                    return;
                }
                int mode = (wandMode(stack) + 1) % MODE_COUNT;
                setMode(stack, mode);
                player.displayClientMessage(Component.translatable("item.duels_ld.arena_wand.mode_set", modeName(mode))
                    .withStyle(ChatFormatting.LIGHT_PURPLE), true);
            });
        });
    }

    private static void handleClick(ServerPlayer player, ItemStack wand, BlockPos clicked, Direction face, boolean leftClick) {
        String arenaName = wandArena(wand);
        if (arenaName == null) {
            player.displayClientMessage(Component.translatable("item.duels_ld.arena_wand.unbound").withStyle(ChatFormatting.RED), true);
            return;
        }
        ArenaManager.Arena arena = ArenaManager.getArena(arenaName);
        if (arena == null) {
            player.displayClientMessage(Component.translatable("duels_ld.command.arena.not_found", arenaName).withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!arena.dimensionId.equals(player.level().dimension().location().toString())) {
            player.displayClientMessage(Component.translatable("duels_ld.command.arena.wrong_dimension_spawn").withStyle(ChatFormatting.RED), true);
            return;
        }

        if (wandMode(wand) == MODE_BOUNDS) {
            if (leftClick) {
                ArenaManager.setPos1(arenaName, clicked.immutable());
                player.displayClientMessage(Component.translatable("duels_ld.command.arena.pos1_set", arenaName).withStyle(ChatFormatting.GREEN), true);
            } else {
                ArenaManager.setPos2(arenaName, clicked.immutable());
                player.displayClientMessage(Component.translatable("duels_ld.command.arena.pos2_set", arenaName).withStyle(ChatFormatting.GREEN), true);
            }
        } else {
            // Spawn mode: place at the block adjacent to the clicked face (where a player stands).
            int team = leftClick ? 2 : 1;
            BlockPos spawnPos = clicked.relative(face).immutable();
            boolean added = ArenaManager.toggleSpawn(arenaName, team, spawnPos, player.getYRot(), player.getXRot());
            player.displayClientMessage(Component.translatable(
                added ? "item.duels_ld.arena_wand.spawn_added" : "item.duels_ld.arena_wand.spawn_removed", team)
                .withStyle(added ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        }
        sendOverlay(player, arenaName);
    }

    private static void openWandScreen(ServerPlayer player) {
        ArenaGuiHandler.sendArenaList(player, ArenaListPayload.OPEN_WAND);
    }

    /** Gives the player a wand pre-bound to an arena (BOUNDS mode). */
    public static void give(ServerPlayer player, String arenaName) {
        ItemStack wand = new ItemStack(ItemRegistry.ARENA_WAND);
        setArena(wand, arenaName);
        setMode(wand, MODE_BOUNDS);
        giveStack(player, wand);
    }

    /** Gives the player an unbound wand; they pick an arena via shift+right-click. */
    public static void giveUnbound(ServerPlayer player) {
        ItemStack wand = new ItemStack(ItemRegistry.ARENA_WAND);
        setMode(wand, MODE_BOUNDS);
        giveStack(player, wand);
    }

    private static void giveStack(ServerPlayer player, ItemStack wand) {
        if (!player.getInventory().add(wand)) {
            player.drop(wand, false);
        }
    }

    /** Binds the player's held wand to the named arena. */
    public static void bind(ServerPlayer player, String arenaName) {
        ItemStack wand = heldWand(player);
        if (wand == null) {
            return;
        }
        setArena(wand, arenaName);
        sendOverlay(player, arenaName);
        player.displayClientMessage(Component.translatable("item.duels_ld.arena_wand.bound_msg", arenaName)
            .withStyle(ChatFormatting.LIGHT_PURPLE), true);
    }

    public static String heldWandArena(ServerPlayer player) {
        ItemStack wand = heldWand(player);
        return wand == null ? null : wandArena(wand);
    }

    private static ItemStack heldWand(ServerPlayer player) {
        if (player.getMainHandItem().is(ItemRegistry.ARENA_WAND)) {
            return player.getMainHandItem();
        }
        if (player.getOffhandItem().is(ItemRegistry.ARENA_WAND)) {
            return player.getOffhandItem();
        }
        return null;
    }

    /** The arena a wand stack is bound to, or {@code null} if unbound / not a wand. */
    public static String wandArena(ItemStack stack) {
        if (stack == null || !stack.is(ItemRegistry.ARENA_WAND)) {
            return null;
        }
        String name = currentTag(stack).getString(TAG_KEY);
        return name.isEmpty() ? null : name;
    }

    public static int wandMode(ItemStack stack) {
        if (stack == null || !stack.is(ItemRegistry.ARENA_WAND)) {
            return MODE_BOUNDS;
        }
        return Math.floorMod(currentTag(stack).getInt(MODE_KEY), MODE_COUNT);
    }

    private static void setArena(ItemStack stack, String name) {
        CompoundTag tag = currentTag(stack);
        tag.putString(TAG_KEY, name);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.duels_ld.arena_wand.bound_name", name)
            .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    private static void setMode(ItemStack stack, int mode) {
        CompoundTag tag = currentTag(stack);
        tag.putInt(MODE_KEY, mode);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static CompoundTag currentTag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    public static Component modeName(int mode) {
        return Component.translatable(mode == MODE_SPAWNS
            ? "item.duels_ld.arena_wand.mode_spawns"
            : "item.duels_ld.arena_wand.mode_bounds");
    }

    /** Pushes the current geometry of the named arena to the player for the in-world overlay. */
    public static void sendOverlay(ServerPlayer player, String arenaName) {
        ArenaManager.Arena arena = ArenaManager.getArena(arenaName);
        if (arena == null) {
            return;
        }
        List<ArenaOverlayPayload.Spawn> spawns = new ArrayList<>();
        for (ArenaManager.SpawnPoint s : arena.team1Spawns) {
            spawns.add(new ArenaOverlayPayload.Spawn(s.pos(), 1, s.yaw()));
        }
        for (ArenaManager.SpawnPoint s : arena.team2Spawns) {
            spawns.add(new ArenaOverlayPayload.Spawn(s.pos(), 2, s.yaw()));
        }
        ServerPlayNetworking.send(player, new ArenaOverlayPayload(arena.dimensionId, arena.pos1, arena.pos2, spawns));
    }
}
