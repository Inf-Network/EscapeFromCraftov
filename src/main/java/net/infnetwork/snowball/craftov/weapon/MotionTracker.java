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
    /** Ticks looked at (see {@link #average}): movement packets sometimes arrive two in one tick and none in the next. */
    private static final int WINDOW = 4;
    /**
     * Most a player covers in one tick (sprint-jumping is about 0.6). A longer step is movement held
     * back and arriving at once, counted as this much so the player still reads as moving; teleports
     * reset the track through their event instead.
     */
    private static final double MAX_STEP = 1.0;
    /**
     * Moving this much faster than the player's own walking pace is running, whatever the client says
     * about sprinting: vanilla sprinting is 1.3 times walking pace.
     */
    private static final double RUN_RATIO = 1.2;
    /** Ticks it has to last, on the ground, before it counts: a shove from a blast does not. */
    private static final int RUN_TICKS = 3;
    /**
     * Ticks of walking pace remembered. Slowing down takes a few ticks (when the player starts aiming,
     * stops sprinting or a speed boost wears off), so the pace to beat is the highest one lately.
     */
    private static final int PACE_WINDOW = 10;

    private static final class Track {
        UUID world;
        double x;
        double z;
        /** Horizontal distance covered in each of the last {@link #WINDOW} ticks. */
        final double[] steps = new double[WINDOW];
        int next;
        /** No position to measure from yet (just joined, teleported, respawned or changed world). */
        boolean fresh = true;
        /** The walking pace the movement-speed attribute allowed in each of the last ticks. */
        final double[] paces = new double[PACE_WINDOW];
        int nextPace;
        boolean pacesSet;
        /** Consecutive ticks spent on the ground well over that pace. */
        int fastTicks;
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
        double walk = walkPace(player);
        if (!track.pacesSet) {
            java.util.Arrays.fill(track.paces, walk);
            track.pacesSet = true;
        }
        track.paces[track.nextPace] = walk;
        track.nextPace = (track.nextPace + 1) % PACE_WINDOW;
        double pace = 0;
        for (double p : track.paces) {
            pace = Math.max(pace, p);
        }
        boolean fast = groundRun(player) && average(track) > RUN_RATIO * pace;
        track.fastTicks = fast ? track.fastTicks + 1 : 0;
    }

    /**
     * Moving on foot over ground that gives normal grip: what can be compared with walking pace.
     * Ice and riding carry a player faster than their legs, and the air has its own pace.
     */
    @SuppressWarnings("deprecation")
    private static boolean groundRun(Player player) {
        if (!player.isOnGround() || player.isInsideVehicle() || player.isFlying() || player.isGliding() || player.isSwimming()) {
            return false;
        }
        return !player.getLocation().subtract(0, 0.2, 0).getBlock().getType().name().contains("ICE");
    }

    /**
     * True while the player has been running on the ground well over their walking pace for a few
     * ticks: sprinting, even if the client never said it started.
     */
    boolean running(Player player) {
        Track track = tracks.get(player.getUniqueId());
        return track != null && track.fastTicks >= RUN_TICKS;
    }

    /**
     * Pace over the last {@link #WINDOW} ticks, robust to how movement packets fall into ticks: the
     * median of the three two-tick averages in it. A plain average reads a steady walk 25% slow
     * whenever one tick's packet arrives with the next tick's (steps x, x, x, 0 average 0.75x; their
     * two-tick averages x, x, x/2 have the median x), and such a tick is common. A stop still shows
     * within two or three ticks: the median drops once two of the three pairs are small (a stop with
     * the opposite key, steps x, 0.1x, 0, 0, reads 0.05x; letting go, x, x, 0, 0 reads x/2 until the
     * next still tick).
     */
    private static double average(Track track) {
        double[] pairs = new double[WINDOW - 1];
        for (int i = 0; i < WINDOW - 1; i++) {
            pairs[i] = (track.steps[(track.next + i) % WINDOW] + track.steps[(track.next + i + 1) % WINDOW]) / 2;
        }
        java.util.Arrays.sort(pairs);
        return pairs[pairs.length / 2];
    }

    /** Blocks per tick the player's movement-speed attribute walks right now. */
    private static double walkPace(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        return (attribute == null ? 0.1 : attribute.getValue()) * BLOCKS_PER_SPEED;
    }

    /** Horizontal speed in blocks per tick, averaged over the last few ticks. */
    double speed(Player player) {
        Track track = tracks.get(player.getUniqueId());
        return track == null ? 0 : average(track);
    }

    /**
     * How close the player is to their full walking pace, 0 (still) to 1. Measured against the pace their
     * movement-speed attribute allows right now, so a player slowed by aiming, armour or a broken leg who
     * walks as fast as they can counts as fully moving.
     */
    double moveFactor(Player player) {
        return Math.min(1.0, speed(player) / Math.max(0.06, walkPace(player)));
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
