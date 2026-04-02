package net.ledok.duels_ld.manager;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.duels_ld.DuelsLdMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DuelsLdMod.MOD_ID);
    private static final File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "duels_config.txt");
    private static final Set<String> authorizedPlayers = new HashSet<>();

    public static void loadConfig() {
        authorizedPlayers.clear();
        if (!configFile.exists()) {
            try {
                Files.write(configFile.toPath(), "# Add one player name per line to authorize them for /duel battle".getBytes());
            } catch (IOException e) {
                LOGGER.error("Could not create default duels config file.", e);
            }
            return;
        }

        try {
            Set<String> lines = Files.lines(configFile.toPath())
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .collect(Collectors.toSet());
            authorizedPlayers.addAll(lines);
            LOGGER.info("Loaded " + authorizedPlayers.size() + " authorized players from duels_config.txt");
        } catch (IOException e) {
            LOGGER.error("Could not read duels config file.", e);
        }
    }

    public static boolean isPlayerAuthorized(String playerName) {
        return authorizedPlayers.contains(playerName);
    }
}
