package net.ledok.duels_ld.manager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Where to send a player when their duel ends, plus the game mode they had before it started.
 *
 * <p>Serialized via {@link #CODEC} so backups can be persisted in {@link DuelBackupStore} and
 * survive a server restart — otherwise a player who is mid-duel (in the PvP dimension) when the
 * server stops would be stranded there on next login with no restore point.
 */
public record PlayerBackup(GameType gameMode, Vec3 position, float yaw, float pitch, ResourceKey<Level> dimension,
                           float health, int food, float saturation) {
    private static final Codec<GameType> GAME_TYPE_CODEC = Codec.STRING.xmap(
        s -> GameType.byName(s, GameType.SURVIVAL),
        GameType::getName
    );

    public static final Codec<PlayerBackup> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            GAME_TYPE_CODEC.optionalFieldOf("gameMode", GameType.SURVIVAL).forGetter(PlayerBackup::gameMode),
            Vec3.CODEC.fieldOf("position").forGetter(PlayerBackup::position),
            Codec.FLOAT.optionalFieldOf("yaw", 0.0f).forGetter(PlayerBackup::yaw),
            Codec.FLOAT.optionalFieldOf("pitch", 0.0f).forGetter(PlayerBackup::pitch),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(PlayerBackup::dimension),
            Codec.FLOAT.optionalFieldOf("health", 20.0f).forGetter(PlayerBackup::health),
            Codec.INT.optionalFieldOf("food", 20).forGetter(PlayerBackup::food),
            Codec.FLOAT.optionalFieldOf("saturation", 5.0f).forGetter(PlayerBackup::saturation)
        ).apply(instance, PlayerBackup::new)
    );

    /** Captures a player's location, facing, game mode, and vitals (HP / hunger) before a duel. */
    public static PlayerBackup capture(ServerPlayer player) {
        return new PlayerBackup(
            player.gameMode.getGameModeForPlayer(),
            player.position(),
            player.getYRot(),
            player.getXRot(),
            player.level().dimension(),
            player.getHealth(),
            player.getFoodData().getFoodLevel(),
            player.getFoodData().getSaturationLevel()
        );
    }

    /** Restores the captured vitals onto a player (used so a duel grants no free heal/feed). */
    public void applyVitals(ServerPlayer player) {
        player.setHealth(Math.min(health, player.getMaxHealth()));
        player.getFoodData().setFoodLevel(food);
        player.getFoodData().setSaturation(saturation);
    }
}
