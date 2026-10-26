package net.ledok.duels_ld.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Saves one player's data now. The duel bank uses it right after items enter or leave an
 * inventory, so the bank on disk and the inventory on disk never disagree for longer than a
 * moment (otherwise a crash before the next autosave would lose or duplicate a stake).
 */
@Mixin(PlayerList.class)
public interface PlayerListInvoker {
    @Invoker("save")
    void duels_ld$save(ServerPlayer player);
}
