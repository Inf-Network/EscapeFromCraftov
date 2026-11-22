package net.infnetwork.snowball.craftov.ai;

import com.destroystokyo.paper.entity.Pathfinder;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Soldiers and doors. A soldier whose path runs through a closed wooden door opens it as it gets
 * there, with the door sound, and closes it again about two seconds after the last soldier went
 * through, once nobody stands in the doorway. Doors a player opened are left as they are. Iron
 * doors and doors still locked in this raid stay shut: the soldier stops in front of them.
 */
final class AiDoors {

    /** What a soldier's path meets next. */
    enum Ahead { CLEAR, LOCKED }

    private record Opened(Raid raid, long closeAt) {
    }

    private final CraftovPlugin plugin;
    /** Doors soldiers opened (lower halves) and when they close again. */
    private final Map<Block, Opened> opened = new HashMap<>();

    AiDoors(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Opens the closed wooden doors on the next stretch of {@code mob}'s path that it is about to
     * walk through, or reports a locked one there.
     */
    Ahead ahead(Mob mob, Raid raid, long now) {
        Pathfinder.PathResult path = mob.getPathfinder().getCurrentPath();
        if (path == null) {
            return Ahead.CLEAR;
        }
        List<Location> points = path.getPoints();
        int next = path.getNextPointIndex();
        Location at = mob.getLocation();
        for (int i = Math.max(0, next - 1); i <= Math.min(points.size() - 1, next + 1); i++) {
            Block lower = lowerHalf(points.get(i).getBlock());
            if (lower == null || lower.getWorld() != at.getWorld()
                    || lower.getLocation().add(0.5, 0, 0.5).distanceSquared(at) > 2.5 * 2.5
                    || !(lower.getBlockData() instanceof Door door)) {
                continue;
            }
            long closeAt = now + plugin.getConfig().getLong("ai.door-close-ticks", 40);
            if (door.isOpen()) {
                if (opened.containsKey(lower)) {
                    // Another soldier on its way through: the door stays open for it too.
                    opened.put(lower, new Opened(raid, closeAt));
                }
                continue;
            }
            if (lower.getType() == Material.IRON_DOOR || raid.lockedDoors.containsKey(Raid.blockKey(lower.getLocation()))) {
                return Ahead.LOCKED;
            }
            RaidManager.setDoor(lower, true);
            sound(lower, Sound.BLOCK_WOODEN_DOOR_OPEN);
            opened.put(lower, new Opened(raid, closeAt));
        }
        return Ahead.CLEAR;
    }

    /** Closes the doors soldiers left open once their time is up and the doorway is empty. */
    void tick(long now) {
        opened.entrySet().removeIf(entry -> {
            Opened door = entry.getValue();
            Block lower = entry.getKey();
            if (door.raid().state != Raid.State.RUNNING || door.raid().world != lower.getWorld()) {
                return true;
            }
            if (now < door.closeAt()) {
                return false;
            }
            if (!(lower.getBlockData() instanceof Door data) || !data.isOpen()) {
                return true; // someone closed it already
            }
            if (occupied(lower)) {
                entry.setValue(new Opened(door.raid(), now + 10));
                return false;
            }
            RaidManager.setDoor(lower, false);
            sound(lower, Sound.BLOCK_WOODEN_DOOR_CLOSE);
            return true;
        });
    }

    /** The lower half of the door at {@code block}, or null if there is no door. */
    private static @Nullable Block lowerHalf(Block block) {
        if (!(block.getBlockData() instanceof Door door)) {
            return null;
        }
        return door.getHalf() == Bisected.Half.TOP ? block.getRelative(BlockFace.DOWN) : block;
    }

    /**
     * Whether a player or a soldier stands in the doorway. Bodies (mannequins), a disconnected
     * player's stand-in and anything else lying there do not count: a body in the doorway kept the
     * door open for the rest of the raid.
     */
    private boolean occupied(Block lower) {
        BoundingBox doorway = new BoundingBox(lower.getX() - 0.2, lower.getY(), lower.getZ() - 0.2,
                lower.getX() + 1.2, lower.getY() + 2, lower.getZ() + 1.2);
        return !lower.getWorld().getNearbyEntities(doorway, entity -> entity instanceof Player player
                ? player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                : plugin.ai().isAi(entity)).isEmpty();
    }

    private static void sound(Block lower, Sound sound) {
        lower.getWorld().playSound(lower.getLocation().add(0.5, 1, 0.5), sound, SoundCategory.BLOCKS, 1f, 0.95f + (float) Math.random() * 0.1f);
    }
}
