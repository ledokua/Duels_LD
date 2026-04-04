package net.ledok.duels_ld.integration;

import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import eu.pb4.placeholders.api.PlaceholderContext;
import net.ledok.duels_ld.DuelsLdMod;
import net.ledok.duels_ld.manager.MMRManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LeaderboardPlaceholders {
    private static final long CACHE_MS = 5000;
    private static long lastUpdateMs = 0;
    private static List<Entry> cached1v1 = List.of();
    private static List<Entry> cached2v2 = List.of();
    private static List<ArenaEntry> cachedArenas = List.of();

    private LeaderboardPlaceholders() {}

    public static void init() {
        Placeholders.register(
            ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "leaderboard_1v1_header"),
            (ctx, arg) -> PlaceholderResult.value(net.minecraft.network.chat.Component.translatable("duels_ld.placeholder.leaderboard_1v1_header"))
        );
        Placeholders.register(
            ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "leaderboard_2v2_header"),
            (ctx, arg) -> PlaceholderResult.value(net.minecraft.network.chat.Component.translatable("duels_ld.placeholder.leaderboard_2v2_header"))
        );
        Placeholders.register(
            ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "leaderboard_1v1"),
            (ctx, arg) -> PlaceholderResult.value(getLine(ctx, Mode.ONE_VS_ONE, arg))
        );
        Placeholders.register(
            ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "leaderboard_2v2"),
            (ctx, arg) -> PlaceholderResult.value(getLine(ctx, Mode.TWO_VS_TWO, arg))
        );
        Placeholders.register(
            ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "arena_status"),
            (ctx, arg) -> PlaceholderResult.value(getArenaLine(ctx, arg))
        );
        Placeholders.register(
            ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "arena_status_header"),
            (ctx, arg) -> PlaceholderResult.value(net.minecraft.network.chat.Component.translatable("duels_ld.placeholder.arena_status_header"))
        );
        Placeholders.register(
            ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "arena_status_all"),
            (ctx, arg) -> PlaceholderResult.value(buildArenaMultiline())
        );
        Placeholders.register(
            ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "queue_1v1"),
            (ctx, arg) -> PlaceholderResult.value(Integer.toString(net.ledok.duels_ld.manager.MatchmakingManager.getQueued1v1Count()))
        );
        Placeholders.register(
            ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "queue_2v2"),
            (ctx, arg) -> PlaceholderResult.value(Integer.toString(net.ledok.duels_ld.manager.MatchmakingManager.getQueued2v2Count()))
        );
        Placeholders.register(
            ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "queue_total"),
            (ctx, arg) -> PlaceholderResult.value(Integer.toString(net.ledok.duels_ld.manager.MatchmakingManager.getQueuedTotalCount()))
        );
    }

    private static String getLine(PlaceholderContext ctx, Mode mode, String arg) {
        int index = parseIndex(arg);
        if (index < 1) {
            return "-";
        }
        List<Entry> list = getLeaderboard(ctx.server(), mode);
        if (index > list.size()) {
            return "-";
        }
        Entry entry = list.get(index - 1);
        return index + ". " + entry.name + " - " + entry.rating;
    }

    private static int parseIndex(String arg) {
        if (arg == null || arg.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(arg.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static List<Entry> getLeaderboard(MinecraftServer server, Mode mode) {
        long now = System.currentTimeMillis();
        if (now - lastUpdateMs > CACHE_MS) {
            cached1v1 = buildLeaderboard(server, MMRManager.getAll1v1Ratings());
            cached2v2 = buildLeaderboard(server, MMRManager.getAll2v2Ratings());
            cachedArenas = buildArenaList();
            lastUpdateMs = now;
        }
        return mode == Mode.ONE_VS_ONE ? cached1v1 : cached2v2;
    }

    private static String getArenaLine(PlaceholderContext ctx, String arg) {
        int index = parseIndex(arg);
        if (index < 1) {
            return "-";
        }
        List<ArenaEntry> list = getArenaList();
        if (index > list.size()) {
            return "-";
        }
        ArenaEntry entry = list.get(index - 1);
        return net.minecraft.network.chat.Component.translatable(
            "duels_ld.placeholder.arena_status_line",
            entry.name,
            net.minecraft.network.chat.Component.translatable(entry.active
                ? "duels_ld.placeholder.arena_status_occupied"
                : "duels_ld.placeholder.arena_status_free")
        ).getString();
    }

    private static List<ArenaEntry> getArenaList() {
        long now = System.currentTimeMillis();
        if (now - lastUpdateMs > CACHE_MS) {
            MinecraftServer server = net.ledok.duels_ld.manager.ArenaManager.getServer();
            if (server != null) {
                cached1v1 = buildLeaderboard(server, MMRManager.getAll1v1Ratings());
                cached2v2 = buildLeaderboard(server, MMRManager.getAll2v2Ratings());
            }
            cachedArenas = buildArenaList();
            lastUpdateMs = now;
        }
        return cachedArenas;
    }

    private static List<ArenaEntry> buildArenaList() {
        List<net.ledok.duels_ld.manager.ArenaManager.Arena> arenas = net.ledok.duels_ld.manager.ArenaManager.listArenas();
        if (arenas.isEmpty()) {
            return List.of();
        }
        List<ArenaEntry> out = new ArrayList<>(arenas.size());
        for (var arena : arenas) {
            out.add(new ArenaEntry(arena.name, net.ledok.duels_ld.manager.ArenaManager.isActive(arena.name)));
        }
        return out;
    }

    private static net.minecraft.network.chat.Component buildArenaMultiline() {
        List<ArenaEntry> list = getArenaList();
        if (list.isEmpty()) {
            return net.minecraft.network.chat.Component.translatable("duels_ld.placeholder.arena_status_none");
        }
        net.minecraft.network.chat.MutableComponent out = net.minecraft.network.chat.Component.empty();
        for (int i = 0; i < list.size(); i++) {
            ArenaEntry entry = list.get(i);
            net.minecraft.network.chat.Component line = net.minecraft.network.chat.Component.translatable(
                "duels_ld.placeholder.arena_status_line",
                entry.name,
                net.minecraft.network.chat.Component.translatable(entry.active
                    ? "duels_ld.placeholder.arena_status_occupied"
                    : "duels_ld.placeholder.arena_status_free")
            );
            out.append(line);
            if (i < list.size() - 1) {
                out.append("\n");
            }
        }
        return out;
    }

    private static List<Entry> buildLeaderboard(MinecraftServer server, Map<UUID, Integer> ratings) {
        if (ratings.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<UUID, Integer>> sorted = new ArrayList<>(ratings.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<UUID, Integer> e) -> e.getValue()).reversed());
        int count = Math.min(10, sorted.size());
        List<Entry> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Map.Entry<UUID, Integer> entry = sorted.get(i);
            String name = "Unknown";
            if (server != null) {
                var profile = server.getProfileCache().get(entry.getKey());
                if (profile.isPresent()) {
                    name = profile.get().getName();
                }
            }
            out.add(new Entry(name, entry.getValue()));
        }
        return out;
    }

    private enum Mode {
        ONE_VS_ONE,
        TWO_VS_TWO
    }

    private static final class Entry {
        private final String name;
        private final int rating;

        private Entry(String name, int rating) {
            this.name = name;
            this.rating = rating;
        }
    }

    private static final class ArenaEntry {
        private final String name;
        private final boolean active;

        private ArenaEntry(String name, boolean active) {
            this.name = name;
            this.active = active;
        }
    }
}
