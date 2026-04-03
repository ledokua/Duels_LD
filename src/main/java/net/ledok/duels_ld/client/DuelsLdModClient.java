package net.ledok.duels_ld.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.duels_ld.network.OpenDuelScreenPayload;
import net.ledok.duels_ld.network.OpenAdminGuiPayload;
import net.ledok.duels_ld.network.SyncRequestsPayload;
import net.ledok.duels_ld.network.SyncMatchmakingSettingsPayload;
import net.ledok.duels_ld.network.SyncEloPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;

public class DuelsLdModClient implements ClientModInitializer {
    private static final KeyMapping OPEN_LOBBY_KEY = KeyBindingHelper.registerKeyBinding(
        new KeyMapping("key.duels_ld.open_lobby", GLFW.GLFW_KEY_B, "key.categories.misc")
    );

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(OpenDuelScreenPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                context.client().setScreen(new DuelRequestScreen());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(SyncRequestsPayload.TYPE, (payload, context) -> {
            List<DuelRequestScreen.RequestEntryData> requests = new ArrayList<>();
            for (SyncRequestsPayload.RequestData data : payload.requests()) {
                requests.add(new DuelRequestScreen.RequestEntryData(data.senderId(), data.senderName(), data.settingsDesc()));
            }
            
            context.client().execute(() -> {
                if (context.client().screen instanceof DuelRequestScreen screen) {
                    screen.setRequests(requests);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(OpenAdminGuiPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                context.client().setScreen(new MatchmakingAdminScreen());
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
                if (context.client().screen instanceof MatchmakingScreen screen) {
                    screen.setElo(payload.elo1v1(), payload.elo2v2());
                }
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_LOBBY_KEY.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new MatchmakingScreen());
                }
            }
        });
    }
}
