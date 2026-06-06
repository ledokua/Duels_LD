package net.ledok.duels_ld.manager;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.duels_ld.network.ArenaOverlayPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;

/**
 * The duel arena selection wand: a tagged item that sets an arena's bounds by clicking blocks.
 * Left-click = pos1, right-click = pos2, for the arena name stored on the wand. After each edit it
 * pushes a fresh {@link ArenaOverlayPayload} so the client overlay stays in sync.
 */
public final class ArenaWand {
    private static final String TAG_KEY = "DuelArenaWand";

    private ArenaWand() {
    }

    public static void register() {
        // Left-click a block: set pos1.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            String arena = wandArena(serverPlayer.getItemInHand(hand));
            if (arena == null) {
                return InteractionResult.PASS;
            }
            handleClick(serverPlayer, arena, pos, true);
            return InteractionResult.SUCCESS;
        });

        // Right-click a block: set pos2.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            String arena = wandArena(serverPlayer.getItemInHand(hand));
            if (arena == null) {
                return InteractionResult.PASS;
            }
            handleClick(serverPlayer, arena, hitResult.getBlockPos(), false);
            return InteractionResult.SUCCESS;
        });
    }

    private static void handleClick(ServerPlayer player, String arenaName, BlockPos pos, boolean isPos1) {
        ArenaManager.Arena arena = ArenaManager.getArena(arenaName);
        if (arena == null) {
            player.sendSystemMessage(Component.translatable("duels_ld.command.arena.not_found", arenaName));
            return;
        }
        if (!arena.dimensionId.equals(player.level().dimension().location().toString())) {
            player.sendSystemMessage(Component.translatable("duels_ld.command.arena.wrong_dimension_spawn").withStyle(ChatFormatting.RED));
            return;
        }
        if (isPos1) {
            ArenaManager.setPos1(arenaName, pos.immutable());
            player.sendSystemMessage(Component.translatable("duels_ld.command.arena.pos1_set", arenaName).withStyle(ChatFormatting.GREEN));
        } else {
            ArenaManager.setPos2(arenaName, pos.immutable());
            player.sendSystemMessage(Component.translatable("duels_ld.command.arena.pos2_set", arenaName).withStyle(ChatFormatting.GREEN));
        }
        sendOverlay(player, arenaName);
    }

    /** Gives the player a wand bound to the named arena. */
    public static void give(ServerPlayer player, String arenaName) {
        ItemStack wand = new ItemStack(Items.BLAZE_ROD);
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_KEY, arenaName);
        wand.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        wand.set(DataComponents.CUSTOM_NAME, Component.translatable("duels_ld.item.arena_wand", arenaName)
            .withStyle(ChatFormatting.LIGHT_PURPLE));
        if (!player.getInventory().add(wand)) {
            player.drop(wand, false);
        }
    }

    /** The arena name a wand stack is bound to, or {@code null} if the stack is not a wand. */
    public static String wandArena(ItemStack stack) {
        if (stack == null || !stack.is(Items.BLAZE_ROD)) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        String name = data.copyTag().getString(TAG_KEY);
        return name.isEmpty() ? null : name;
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
