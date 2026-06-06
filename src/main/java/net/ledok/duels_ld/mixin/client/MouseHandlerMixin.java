package net.ledok.duels_ld.mixin.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.duels_ld.item.ArenaWandItem;
import net.ledok.duels_ld.network.ArenaWandModePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void duels_ld$onScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        Player player = Minecraft.getInstance().player;
        if (player != null && player.isShiftKeyDown()
            && player.getMainHandItem().getItem() instanceof ArenaWandItem) {
            ClientPlayNetworking.send(new ArenaWandModePayload(vertical > 0));
            ci.cancel();
        }
    }
}
