package net.ledok.duels_ld.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.duels_ld.DuelsLdMod;
import net.ledok.eden_ld.api.hub.EdenPartyApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Thin, optional bridge to Eden_LD, which owns parties and blocks damage between partymates and
 * guildmates. Eden_LD is a soft dependency: every method here checks {@link #isAvailable()} first,
 * and the Eden classes are only touched inside {@link Impl}, so nothing links against Eden when it
 * is absent. Server thread only.
 */
public final class EdenBridge {
    /** The single suspension reason Duels uses; Eden stacks reasons, so one per mod is enough. */
    public static final ResourceLocation MATCH_REASON = ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "match");

    private static final boolean AVAILABLE = FabricLoader.getInstance().isModLoaded("eden_ld");

    private EdenBridge() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Snapshot of an Eden party; never cache it (members and leader change). */
    public record Party(UUID id, String name, UUID leader, List<UUID> members) {
    }

    public static Optional<Party> getParty(MinecraftServer server, UUID player) {
        return AVAILABLE ? Impl.getParty(server, player) : Optional.empty();
    }

    /**
     * Lets partymates and guildmates hurt each other for the length of a match. Duels' own team
     * rules decide who may hit whom while this is in effect.
     */
    public static void suspendProtection(ServerPlayer player) {
        if (AVAILABLE && player != null) {
            Impl.suspend(player);
        }
    }

    public static void resumeProtection(ServerPlayer player) {
        if (AVAILABLE && player != null) {
            Impl.resume(player);
        }
    }

    /**
     * Whether the player accepts personal wager challenges (Eden hub setting
     * {@code duels_ld:wager.personal_challenges}). True until Eden's settings API is available.
     */
    public static boolean personalChallengesAllowed(MinecraftServer server, UUID player) {
        return !AVAILABLE || Impl.setting(server, player, "duels_ld:wager.personal_challenges", true);
    }

    /** Whether the player turned off the "you'll lose your stake" popup (Eden hub setting). */
    public static boolean skipLossWarning(MinecraftServer server, UUID player) {
        return AVAILABLE && Impl.setting(server, player, "duels_ld:wager.skip_loss_warning", false);
    }

    /** Eden's setter for hub settings, when this Eden version has one (looked up once). */
    private static java.lang.reflect.Method settingSetter;
    private static boolean setterLooked;

    private static java.lang.reflect.Method setter() {
        if (!setterLooked) {
            setterLooked = true;
            if (AVAILABLE) {
                try {
                    settingSetter = Class.forName("net.ledok.eden_ld.api.hub.EdenSettingsApi")
                        .getMethod("setBoolean", MinecraftServer.class, UUID.class, String.class, boolean.class);
                } catch (ReflectiveOperationException ignored) {
                    settingSetter = null;
                }
            }
        }
        return settingSetter;
    }

    /** Whether "don't ask again" can be stored (Eden has a settings setter). */
    public static boolean canSetSkipLossWarning() {
        return setter() != null;
    }

    /** Stores the "skip the loss warning" hub setting; false when Eden can't. */
    public static boolean setSkipLossWarning(MinecraftServer server, UUID player, boolean skip) {
        java.lang.reflect.Method method = setter();
        if (method == null) {
            return false;
        }
        try {
            method.invoke(null, server, player, "duels_ld:wager.skip_loss_warning", skip);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * Files a complaint in Eden's Moderation → Reports. Returns the report id, or -1 when Eden
     * refused it (blank text, rate limit) or isn't there.
     */
    public static long report(MinecraftServer server, UUID reporter, String reference, String text, List<String> log) {
        return AVAILABLE ? Impl.report(server, reporter, reference, text, log) : -1;
    }

    /** Calls {@code listener(partyId, player)} on every Eden party join or leave (kicks and disbands included). */
    public static void onMembershipChange(BiConsumer<UUID, UUID> listener) {
        if (AVAILABLE) {
            Impl.listen(listener);
        }
    }

    private static final class Impl {
        static Optional<Party> getParty(MinecraftServer server, UUID player) {
            return EdenPartyApi.getParty(server, player)
                .map(info -> new Party(info.id(), info.name(), info.leader(), List.copyOf(info.members())));
        }

        static void suspend(ServerPlayer player) {
            EdenPartyApi.suspendFriendlyFireProtection(player, MATCH_REASON);
        }

        static void resume(ServerPlayer player) {
            EdenPartyApi.resumeFriendlyFireProtection(player, MATCH_REASON);
        }

        static boolean setting(MinecraftServer server, UUID player, String key, boolean def) {
            return net.ledok.eden_ld.api.hub.EdenSettingsApi.getBoolean(server, player, key, def);
        }

        static long report(MinecraftServer server, UUID reporter, String reference, String text, List<String> log) {
            return net.ledok.eden_ld.api.hub.EdenModerationApi.report(server, reporter, "duels_ld:wager", reference, text, log);
        }

        static void listen(BiConsumer<UUID, UUID> listener) {
            EdenPartyApi.addMembershipListener(new EdenPartyApi.MembershipListener() {
                @Override
                public void onJoin(UUID partyId, UUID player) {
                    listener.accept(partyId, player);
                }

                @Override
                public void onLeave(UUID partyId, UUID player) {
                    listener.accept(partyId, player);
                }
            });
        }
    }
}
