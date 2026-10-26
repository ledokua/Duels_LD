package net.ledok.duels_ld.wager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.duels_ld.DuelsLdMod;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Every number of the wager-duel rules, in {@code config/duels_ld/wager.json}. Read on server start
 * and by {@code /duel reload}; missing fields keep their defaults (the moderator's plan values).
 */
public final class WagerConfig {
    /** Master switch; wager duels also need Eden_LD and Economy_LD (mail). */
    public boolean enabled = true;
    /** How long a listing (or personal challenge) stays up, while nobody is preparing to fight it. */
    public int listingMinutes = 30;
    public int readyCheckSeconds = 30;
    public int stakeSlots = 9;
    public int maxOffersPerListing = 5;
    public int maxActiveOffersPerPlayer = 3;
    /** A stake must have at least this many item stacks, or {@link #minCoins} coins. */
    public int minItems = 1;
    public long minCoins = 50;
    /** 0 = no maximum. */
    public long maxCoins = 0;
    public int fightMinutes = 5;
    /** Reconnect window once the fight started; past it the opponent wins. */
    public int fightReconnectSeconds = 60;
    /** An author or challenger offline this long while their listing/offer waits loses it (refunded). */
    public int waitingOfflineSeconds = 120;
    /** Pause before a new listing after cancelling one. */
    public int relistCooldownSeconds = 60;
    /** Failed ready checks within the window that trigger the block. */
    public int readyFailLimit = 3;
    public int readyFailWindowMinutes = 10;
    public int readyFailBlockMinutes = 10;
    /** Pause before challenging the same player again after they declined. */
    public int rechallengeCooldownSeconds = 120;
    /** Taking damage this recently delays the start (up to the same time), then fails the check. */
    public int recentCombatSeconds = 15;
    public int commentMaxLength = 60;
    public int reportMaxLength = 300;
    /** Item ids that can't be staked (filled containers are always refused). Tag duels_ld:wager_banned works too. */
    public List<String> bannedItems = new ArrayList<>();

    private static WagerConfig current = new WagerConfig();

    public static WagerConfig get() {
        return current;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("duels_ld").resolve("wager.json");
    }

    /** Loads the file (writing defaults when it's missing) and rewrites it so new fields appear. */
    public static void load() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Path file = path();
        WagerConfig loaded = null;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                loaded = gson.fromJson(reader, WagerConfig.class);
            } catch (Exception e) {
                DuelsLdMod.LOGGER.error("Failed to read {}, keeping the current wager settings.", file, e);
                return;
            }
        }
        current = loaded != null ? loaded : new WagerConfig();
        if (current.bannedItems == null) {
            current.bannedItems = new ArrayList<>();
        }
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                gson.toJson(current, writer);
            }
        } catch (Exception e) {
            DuelsLdMod.LOGGER.error("Failed to write {}.", file, e);
        }
    }
}
