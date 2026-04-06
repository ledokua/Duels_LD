package net.ledok.duels_ld.manager;

import net.ledok.busylib.BusyState;

import java.util.UUID;

public class ActivityManager {
    private static final String BUSY_REASON = "duels_ld";

    public static boolean isPlayerBusy(UUID playerUUID) {
        return BusyState.isBusy(playerUUID);
    }

    public static void setPlayerBusy(UUID playerUUID) {
        BusyState.setBusy(playerUUID, BUSY_REASON);
    }

    public static void setPlayerFree(UUID playerUUID) {
        BusyState.clearBusy(playerUUID, BUSY_REASON);
    }
}
