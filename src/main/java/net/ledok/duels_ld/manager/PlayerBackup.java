package net.ledok.duels_ld.manager;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class PlayerBackup {
    private final GameType gameMode;
    private final Vec3 position;
    private final ResourceKey<Level> dimension;

    public PlayerBackup(GameType gameMode, Vec3 position, ResourceKey<Level> dimension) {
        this.gameMode = gameMode;
        this.position = position;
        this.dimension = dimension;
    }

    public GameType getGameMode() {
        return gameMode;
    }

    public Vec3 getPosition() {
        return position;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }
}
