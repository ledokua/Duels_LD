package net.ledok.duels_ld.mixin;

import net.ledok.duels_ld.manager.ArenaCleanup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells the arena cleanup about freshly spawned entities (summons, projectiles, drops,
 * {@code /summon}). Unlike Fabric's entity-load event this never fires for entities loading from
 * disk with their chunk, so a pet or an arena fixture loading mid-fight isn't mistaken for
 * something the fight created.
 */
@Mixin(ServerLevel.class)
public class ServerLevelFreshEntityMixin {

    @Inject(method = "addFreshEntity", at = @At("RETURN"))
    private void duels_ld$trackFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            ArenaCleanup.onFreshEntity((ServerLevel) (Object) this, entity);
        }
    }
}
