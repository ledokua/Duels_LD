package net.ledok.duels_ld.mixin;

import net.ledok.duels_ld.manager.DuelManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerDropMixin {
    @Inject(
        method = "drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void duelsld$blockDropShort(ItemStack stack, boolean throwRandomly, CallbackInfoReturnable<ItemEntity> cir) {
        if (shouldBlockDrop()) {
            if (!stack.isEmpty() && (Object) this instanceof ServerPlayer player) {
                player.getInventory().placeItemBackInInventory(stack);
                player.containerMenu.broadcastChanges();
            }
            cir.setReturnValue(null);
        }
    }

    @Inject(
        method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void duelsld$blockDrop(ItemStack stack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<ItemEntity> cir) {
        if (shouldBlockDrop()) {
            if (!stack.isEmpty() && (Object) this instanceof ServerPlayer player) {
                player.getInventory().placeItemBackInInventory(stack);
                player.containerMenu.broadcastChanges();
            }
            cir.setReturnValue(null);
        }
    }

    @Inject(
        method = "dropEquipment",
        at = @At("HEAD"),
        cancellable = true
    )
    private void duelsld$blockDropEquipment(CallbackInfo ci) {
        if (shouldBlockDrop()) {
            ci.cancel();
        }
    }

    private boolean shouldBlockDrop() {
        if (!((Object) this instanceof ServerPlayer player)) {
            return false;
        }
        return DuelManager.isPlayerInArena(player);
    }
}
