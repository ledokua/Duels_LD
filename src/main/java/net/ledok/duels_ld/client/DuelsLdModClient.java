package net.ledok.duels_ld.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.duels_ld.network.OpenAdminGuiPayload;
import net.ledok.duels_ld.network.OpenLobbyRequestPayload;
import net.ledok.duels_ld.network.OpenLobbyScreenPayload;
import net.ledok.duels_ld.network.PartyStatusPayload;
import net.ledok.duels_ld.network.QueueStatePayload;
import net.ledok.duels_ld.network.SyncMatchmakingSettingsPayload;
import net.ledok.duels_ld.network.SyncEloPayload;
import net.ledok.duels_ld.network.ArenaOverlayPayload;
import net.ledok.duels_ld.network.ArenaListPayload;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class DuelsLdModClient implements ClientModInitializer {
    private static final KeyMapping OPEN_LOBBY_KEY = KeyBindingHelper.registerKeyBinding(
        new KeyMapping("key.duels_ld.open_lobby", GLFW.GLFW_KEY_B, "key.categories.misc")
    );

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(OpenAdminGuiPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                context.client().setScreen(new MatchmakingAdminScreen());
            });
        });
        
        ClientPlayNetworking.registerGlobalReceiver(OpenLobbyScreenPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                context.client().setScreen(new MatchmakingScreen());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(SyncMatchmakingSettingsPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                if (context.client().screen instanceof MatchmakingAdminScreen screen) {
                    screen.applySettings(payload);
                    return;
                }
                MatchmakingAdminScreen screen = new MatchmakingAdminScreen();
                context.client().setScreen(screen);
                screen.applySettings(payload);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(SyncEloPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                MatchmakingScreen.applyElo(payload.elo1v1(), payload.elo2v2(), payload.record1v1(), payload.record2v2());
                if (context.client().screen instanceof MatchmakingScreen screen) {
                    // screen will pick up static values on next tick
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(PartyStatusPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> MatchmakingScreen.applyPartyStatus(payload));
        });

        ClientPlayNetworking.registerGlobalReceiver(QueueStatePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> MatchmakingScreen.applyQueueState(payload.in1v1(), payload.in2v2()));
        });

        ClientPlayNetworking.registerGlobalReceiver(net.ledok.duels_ld.network.RewardStatusPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> MatchmakingScreen.applyRewardStatus(payload.enabled(), payload.earnedToday(), payload.dailyCap()));
        });

        ClientPlayNetworking.registerGlobalReceiver(ArenaOverlayPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> ArenaOverlayRenderer.setSnapshot(payload));
        });

        ClientPlayNetworking.registerGlobalReceiver(ArenaListPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> ArenaManagerScreen.apply(payload.target(), payload.arenas()));
        });

        net.minecraft.client.gui.screens.MenuScreens.register(net.ledok.duels_ld.wager.WagerStakeMenu.TYPE, WagerStakeScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(net.ledok.duels_ld.network.OpenWagerReviewPayload.TYPE, (payload, context) ->
            context.client().execute(() -> context.client().setScreen(new WagerReviewScreen(payload))));
        ClientPlayNetworking.registerGlobalReceiver(net.ledok.duels_ld.network.WagerStatusPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                var screen = context.client().screen;
                if (payload.containerId() < 0 && screen instanceof WagerReviewScreen review) {
                    review.showStatus(payload.ok(), payload.message());
                } else if (screen instanceof WagerStakeScreen stake && stake.getMenu().containerId == payload.containerId()) {
                    stake.showStatus(payload.message());
                } else if (context.client().player != null && !payload.ok()) {
                    context.client().player.displayClientMessage(payload.message(), false);
                }
            }));

        ArenaOverlayRenderer.register();

        // Static session state must not leak across servers; the next server re-syncs it.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            MatchmakingScreen.resetSessionState();
            ArenaManagerScreen.resetSessionState();
            ArenaOverlayRenderer.clearSnapshot();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_LOBBY_KEY.consumeClick()) {
                if (client.player != null && client.screen == null) {
                    ClientPlayNetworking.send(new OpenLobbyRequestPayload());
                }
            }
        });
    }
}
