package net.ledok.duels_ld;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.ledok.duels_ld.command.DuelCommand;
import net.ledok.duels_ld.manager.ArenaManager;
import net.ledok.duels_ld.manager.BattleManager;
import net.ledok.duels_ld.manager.ConfigManager;
import net.ledok.duels_ld.manager.DuelManager;
import net.ledok.duels_ld.manager.MatchmakingConfigManager;
import net.ledok.duels_ld.manager.MatchmakingManager;
import net.ledok.duels_ld.manager.StatsManager;
import net.ledok.duels_ld.network.AcceptRequestPayload;
import net.ledok.duels_ld.network.DeclineRequestPayload;
import net.ledok.duels_ld.network.JoinQueuePayload;
import net.ledok.duels_ld.network.LeaveQueuePayload;
import net.ledok.duels_ld.network.OpenAdminGuiPayload;
import net.ledok.duels_ld.network.OpenDuelScreenPayload;
import net.ledok.duels_ld.network.SyncRequestsPayload;
import net.ledok.duels_ld.network.SyncMatchmakingSettingsPayload;
import net.ledok.duels_ld.network.UpdateMatchmakingSettingsPayload;
import net.ledok.duels_ld.registry.ItemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DuelsLdMod implements ModInitializer {
    public static final String MOD_ID = "duels_ld";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ItemRegistry.initialize();
        ConfigManager.loadConfig();
        StatsManager.init();
        MatchmakingConfigManager.init();
        ArenaManager.init();
        
        PayloadTypeRegistry.playC2S().register(AcceptRequestPayload.TYPE, AcceptRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(DeclineRequestPayload.TYPE, DeclineRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(JoinQueuePayload.TYPE, JoinQueuePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(LeaveQueuePayload.TYPE, LeaveQueuePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(UpdateMatchmakingSettingsPayload.TYPE, UpdateMatchmakingSettingsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenDuelScreenPayload.TYPE, OpenDuelScreenPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncRequestsPayload.TYPE, SyncRequestsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenAdminGuiPayload.TYPE, OpenAdminGuiPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncMatchmakingSettingsPayload.TYPE, SyncMatchmakingSettingsPayload.CODEC);
        
        DuelManager.init();
        BattleManager.init();
        MatchmakingManager.init();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            DuelCommand.register(dispatcher);
        });
    }
}
