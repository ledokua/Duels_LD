package net.ledok.duels_ld;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.duels_ld.command.DuelCommand;
import net.ledok.duels_ld.integration.LeaderboardPlaceholders;
import net.ledok.duels_ld.manager.ArenaManager;
import net.ledok.duels_ld.manager.DuelManager;
import net.ledok.duels_ld.manager.MatchmakingConfigManager;
import net.ledok.duels_ld.manager.MatchmakingManager;
import net.ledok.duels_ld.manager.MMRManager;
import net.ledok.duels_ld.manager.PartyManager;
import net.ledok.duels_ld.manager.StatsManager;
import net.ledok.duels_ld.network.JoinQueuePayload;
import net.ledok.duels_ld.network.LeaveQueuePayload;
import net.ledok.duels_ld.network.OpenAdminGuiPayload;
import net.ledok.duels_ld.network.OpenLobbyRequestPayload;
import net.ledok.duels_ld.network.OpenLobbyScreenPayload;
import net.ledok.duels_ld.network.PartyAcceptPayload;
import net.ledok.duels_ld.network.PartyInvitePayload;
import net.ledok.duels_ld.network.QueueStatePayload;
import net.ledok.duels_ld.network.RequestEloPayload;
import net.ledok.duels_ld.network.SyncEloPayload;
import net.ledok.duels_ld.network.SyncMatchmakingSettingsPayload;
import net.ledok.duels_ld.network.UpdateMatchmakingSettingsPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DuelsLdMod implements ModInitializer {
    public static final String MOD_ID = "duels_ld";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(JoinQueuePayload.TYPE, JoinQueuePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(LeaveQueuePayload.TYPE, LeaveQueuePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(UpdateMatchmakingSettingsPayload.TYPE, UpdateMatchmakingSettingsPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestEloPayload.TYPE, RequestEloPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PartyInvitePayload.TYPE, PartyInvitePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PartyAcceptPayload.TYPE, PartyAcceptPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(OpenLobbyRequestPayload.TYPE, OpenLobbyRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenAdminGuiPayload.TYPE, OpenAdminGuiPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncMatchmakingSettingsPayload.TYPE, SyncMatchmakingSettingsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncEloPayload.TYPE, SyncEloPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenLobbyScreenPayload.TYPE, OpenLobbyScreenPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(QueueStatePayload.TYPE, QueueStatePayload.CODEC);

        StatsManager.init();
        MatchmakingConfigManager.init();
        ArenaManager.init();
        MMRManager.init();
        PartyManager.init();
        
        DuelManager.init();
        MatchmakingManager.init();
        if (FabricLoader.getInstance().isModLoaded("placeholder-api")) {
            LeaderboardPlaceholders.init();
        } else {
            LOGGER.info("Placeholder API not present; leaderboard placeholders are disabled.");
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            DuelCommand.register(dispatcher);
        });
    }
}
