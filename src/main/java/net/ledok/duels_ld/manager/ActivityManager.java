package net.ledok.duels_ld.manager;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ActivityManager {
    private static final Set<UUID> busyPlayers = ConcurrentHashMap.newKeySet();

    public static boolean isPlayerBusy(UUID playerUUID) {
        return busyPlayers.contains(playerUUID);
    }

    public static void setPlayerBusy(UUID playerUUID) {
        busyPlayers.add(playerUUID);
    }

    public static void setPlayerFree(UUID playerUUID) {
        busyPlayers.remove(playerUUID);
    }
}
