package net.infnetwork.snowball.craftov.weapon;

import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How fast each player is really moving, measured on the server from how far they get each tick.
 * Bukkit's velocity cannot tell: a player's own walking and running never write it (only knockback
 * and explosions do), so it reads about zero while they run across a street.
 */
final class MotionTracker {

    /** Vanilla walking pace on flat ground in blocks per tick (sprinting 0.28, sneaking 0.065). */
    static final double WALK_SPEED = 0.216;
    /** Blocks per tick walked per point of the movement-speed attribute (the default 0.1 walks 0.216). */
    private static final double BLOCKS_PER_SPEED = 2.1585;
    /** Ticks averaged: movement packets sometimes arrive two in one tick and none in the next. */
    private static final int WINDOW = 4;
    /**
     * Most a player covers in one tick (sprint-jumping is about 0.6). A longer step is movement held
     * back and arriving at once, counted as this much so the player still reads as moving; teleports
     * reset the track through their event instead.
     */
    private static final double MAX_STEP = 1.0;

    private static final class Track {
        UUID world;
        double x;
        double z;
        /** Horizontal distance covered in each of the last {@link #WINDOW} ticks. */
        final double[] steps = new double[WINDOW];
        int next;
        /** No position to measure from yet (just joined, teleported, respawned or changed world). */
        boolean fresh = true;
    }

    private final Map<UUID, Track> tracks = new HashMap<>();

    /** Records where the player is this tick; call once per tick for every online player. */
    void sample(Player player) {
        Location at = player.getLocation();
        Track track = tracks.computeIfAbsent(player.getUniqueId(), id -> new Track());
        UUID world = at.getWorld().getUID();
        double step = 0;
        if (!track.fresh && world.equals(track.world)) {
            double dx = at.getX() - track.x;
            double dz = at.getZ() - track.z;
            step = Math.min(MAX_STEP, Math.sqrt(dx * dx + dz * dz));
        } else {
            java.util.Arrays.fill(track.steps, 0);
        }
        track.fresh = false;
        track.world = world;
        track.x = at.getX();
        track.z = at.getZ();
        track.steps[track.next] = step;
        track.next = (track.next + 1) % WINDOW;
    }

    /** Horizontal speed in blocks per tick, averaged over the last few ticks. */
    double speed(Player player) {
        Track track = tracks.get(player.getUniqueId());
        if (track == null) {
            return 0;
        }
        double sum = 0;
        for (double step : track.steps) {
            sum += step;
        }
        return sum / WINDOW;
    }

    /**
     * How close the player is to their full walking pace, 0 (still) to 1. Measured against the pace their
     * movement-speed attribute allows right now, so a player slowed by aiming, armour or a broken leg who
     * walks as fast as they can counts as fully moving.
     */
    double moveFactor(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        double walk = (attribute == null ? 0.1 : attribute.getValue()) * BLOCKS_PER_SPEED;
        return Math.min(1.0, speed(player) / Math.max(0.06, walk));
    }

    /** Forgets the last position: the next tick measures from wherever the player lands. */
    void reset(Player player) {
        Track track = tracks.get(player.getUniqueId());
        if (track != null) {
            track.fresh = true;
        }
    }

    void forget(UUID player) {
        tracks.remove(player);
    }
}
