package net.ledok.duels_ld.manager;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.duels_ld.network.ArenaActionPayload;
import net.ledok.duels_ld.network.ArenaListPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Server side of the arena manager GUI: applies {@link ArenaActionPayload} edits (op-only) and
 * pushes {@link ArenaListPayload} snapshots back to the client.
 */
public final class ArenaGuiHandler {
    private ArenaGuiHandler() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ArenaActionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            player.server.execute(() -> {
                if (!player.hasPermissions(2)) {
                    return;
                }
                apply(player, payload);
                sendArenaList(player, ArenaListPayload.REFRESH);
            });
        });
    }

    private static void apply(ServerPlayer player, ArenaActionPayload p) {
        String name = p.name();
        switch (p.action()) {
            case "create" -> {
                if (ArenaManager.createArena(name, player.level().dimension())) {
                    ArenaWand.bind(player, name);
                }
            }
            case "delete" -> ArenaManager.removeArena(name);
            case "clone" -> ArenaManager.cloneArena(name, p.arg());
            case "bind" -> ArenaWand.bind(player, name);
            case "rename" -> {
                if (ArenaManager.renameArena(name, p.arg()) && name.equals(ArenaWand.heldWandArena(player))) {
                    ArenaWand.bind(player, p.arg());
                }
            }
            case "wand" -> {
                ArenaWand.give(player, name);
                ArenaWand.sendOverlay(player, name);
            }
            case "tp" -> teleportToArena(player, name);
            case "pos1" -> setBounds(player, name, true);
            case "pos2" -> setBounds(player, name, false);
            case "addspawn" -> addSpawnHere(player, name, "2".equals(p.arg()) ? 2 : 1);
            default -> {
            }
        }
    }

    private static boolean inArenaDimension(ServerPlayer player, ArenaManager.Arena arena) {
        if (!arena.dimensionId.equals(player.level().dimension().location().toString())) {
            player.sendSystemMessage(Component.translatable("duels_ld.command.arena.wrong_dimension_spawn").withStyle(ChatFormatting.RED));
            return false;
        }
        return true;
    }

    private static void setBounds(ServerPlayer player, String name, boolean isPos1) {
        ArenaManager.Arena arena = ArenaManager.getArena(name);
        if (arena == null || !inArenaDimension(player, arena)) {
            return;
        }
        if (isPos1) {
            ArenaManager.setPos1(name, player.blockPosition());
        } else {
            ArenaManager.setPos2(name, player.blockPosition());
        }
        ArenaWand.sendOverlay(player, name);
    }

    private static void addSpawnHere(ServerPlayer player, String name, int team) {
        ArenaManager.Arena arena = ArenaManager.getArena(name);
        if (arena == null || !inArenaDimension(player, arena)) {
            return;
        }
        ArenaManager.addSpawn(name, team, player.blockPosition(), player.getYRot(), player.getXRot());
        ArenaWand.sendOverlay(player, name);
    }

    private static void teleportToArena(ServerPlayer player, String name) {
        ArenaManager.Arena arena = ArenaManager.getArena(name);
        if (arena == null) {
            return;
        }
        ServerLevel level = player.server.getLevel(arena.getDimensionKey());
        if (level == null) {
            return;
        }
        Vec3 target = null;
        if (arena.pos1 != null) {
            target = new Vec3(arena.pos1.getX() + 0.5, arena.pos1.getY(), arena.pos1.getZ() + 0.5);
        } else if (!arena.team1Spawns.isEmpty()) {
            target = arena.team1Spawns.get(0).center();
        } else if (!arena.team2Spawns.isEmpty()) {
            target = arena.team2Spawns.get(0).center();
        }
        if (target == null) {
            player.sendSystemMessage(Component.translatable("duels_ld.command.arena.tp_no_target", name).withStyle(ChatFormatting.RED));
            return;
        }
        player.teleportTo(level, target.x, target.y, target.z, player.getYRot(), player.getXRot());
    }

    public static void sendArenaList(ServerPlayer player, int target) {
        List<ArenaListPayload.Entry> entries = new ArrayList<>();
        for (ArenaManager.Arena a : ArenaManager.listArenas()) {
            entries.add(new ArenaListPayload.Entry(
                a.name, a.dimensionId, ArenaManager.hasBounds(a),
                a.team1Spawns.size(), a.team2Spawns.size(),
                ArenaManager.isReadyFor(a, 1), ArenaManager.isReadyFor(a, 2),
                ArenaManager.isActive(a.name), ArenaManager.spawnsOutsideBounds(a)));
        }
        ServerPlayNetworking.send(player, new ArenaListPayload(target, entries));
    }
}
