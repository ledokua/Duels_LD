package net.ledok.duels_ld.manager;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Routes player logout/login events into {@link DuelManager}, which owns the disconnect grace,
 * timer pausing, and (deferred, restart-safe) restore logic.
 */
public final class DuelConnectionListener {
    private DuelConnectionListener() {
    }

    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            DuelManager.handlePlayerDisconnect(server, handler.getPlayer()));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID id = handler.getPlayer().getUUID();
            // Defer off the JOIN event: it fires before the player is fully registered in the
            // destination world's chunk tracking, and a teleport here corrupts that state
            // (DistanceManager NPE + "Force-added player with duplicate UUID" on next reconnect).
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) {
                    DuelManager.handlePlayerReconnect(server, player);
                }
            });
        });
    }
}
