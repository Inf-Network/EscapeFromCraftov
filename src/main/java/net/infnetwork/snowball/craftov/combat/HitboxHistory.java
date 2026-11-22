package net.infnetwork.snowball.craftov.combat;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.raid.Raid;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Lag compensation for player shots.
 *
 * <p>A vanilla client draws everyone else where they were a moment ago: the server's position updates
 * take half the ping to arrive, come every 2 (players) or 3 (soldiers) ticks and are smoothed over about
 * 3 more, and the shot then takes the other half of the ping to come back. At 100 ms ping that is
 * 250 to 300 ms, in which a soldier strafing at 2.7 blocks a second moves about 0.7 blocks: more than
 * the 0.3 half-width of its box, so a crosshair held dead on a moving target missed and players had to
 * lead by a body width (two against a sprinting player). The client cannot be made to draw the present
 * or to report its own hits, so the server looks back instead.
 *
 * <p>Every tick this records the hitbox of every living thing in a raid world (the last
 * {@link #SAMPLES} ticks, with the time each was taken), and a player's shot is tested against the boxes
 * as they were {@code ping + combat.lag-compensation.extra-ms} ago, at most {@code max-ms}, blended
 * between the two samples either side. The ping Paper reports is the round trip, which already covers
 * both legs; the extra covers the update interval and the client's smoothing. Blocks are not rewound,
 * so a target that has just stepped behind cover can still be hit for up to max-ms. Soldiers shoot at
 * the present (their aim is computed on the server).
 */
public final class HitboxHistory {

    /** Samples kept per entity: a second, well past the longest rewind. */
    static final int SAMPLES = 20;
    /** Farther than this between two samples is a teleport, not movement to blend across. */
    private static final double TELEPORT = 4.0;
    /**
     * How far outside the ray's own reach a rewound box is still looked for, by where the entity is now:
     * past what a sprint-jump covers in the longest rewind.
     */
    private static final double REACH = 4.0;

    private final CraftovPlugin plugin;
    private final Map<UUID, Track> tracks = new HashMap<>();

    public HitboxHistory(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::record, 1L, 1L);
    }

    /**
     * A round meeting an entity's box as the shooter saw it.
     *
     * @param point    where the round met the (rewound) box: where the shooter saw it land
     * @param body     the same spot on the entity's box as it stands now, which hit zones are read from
     * @param distance from the start of the ray to {@code point}
     */
    public record Hit(LivingEntity entity, Vector point, Vector body, double distance) {
    }

    private void record() {
        long now = System.nanoTime();
        int tick = Bukkit.getCurrentTick();
        for (Raid raid : plugin.raids().raids()) {
            for (LivingEntity entity : raid.world.getLivingEntities()) {
                if (entity instanceof ArmorStand || entity.isDead()) {
                    continue;
                }
                tracks.computeIfAbsent(entity.getUniqueId(), id -> new Track()).add(raid.world, entity.getBoundingBox(), now, tick);
            }
        }
        if (tick % 20 == 0) {
            tracks.values().removeIf(track -> tick - track.lastTick > SAMPLES);
        }
    }

    /**
     * How far back {@code shooter}'s shots look: {@code ping + extra-ms}, at most {@code max-ms}, in
     * milliseconds. 0 for soldiers and with compensation off.
     */
    public int rewindMillis(LivingEntity shooter) {
        if (!(shooter instanceof Player player) || !plugin.getConfig().getBoolean("combat.lag-compensation.enabled", true)) {
            return 0;
        }
        int max = Math.max(0, plugin.getConfig().getInt("combat.lag-compensation.max-ms", 300));
        int extra = plugin.getConfig().getInt("combat.lag-compensation.extra-ms", 100);
        return Math.max(0, Math.min(max, player.getPing() + extra));
    }

    /**
     * The nearest entity passing {@code filter} that a ray from {@code from} along {@code dir} meets
     * within {@code maxDistance}, each tested by its box {@code rewindMillis} ago (now when 0).
     */
    public @Nullable Hit trace(World world, Vector from, Vector dir, double maxDistance, Predicate<Entity> filter, int rewindMillis) {
        if (maxDistance <= 0) {
            return null;
        }
        Vector direction = dir.clone().normalize();
        long rewind = rewindMillis * 1_000_000L;
        long now = System.nanoTime();
        BoundingBox span = BoundingBox.of(from, from).expandDirectional(direction.clone().multiply(maxDistance));
        if (rewind > 0) {
            span.expand(REACH);
        }
        Hit best = null;
        for (Entity entity : world.getNearbyEntities(span, filter)) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            BoundingBox live = living.getBoundingBox();
            BoundingBox box = rewind > 0 ? boxAt(living, live, now - rewind, now) : live;
            RayTraceResult result = box.rayTrace(from, direction, maxDistance);
            if (result == null) {
                continue;
            }
            Vector point = result.getHitPosition();
            double distance = from.distance(point);
            if (best == null || distance < best.distance()) {
                best = new Hit(living, point, carry(point, box, live), distance);
            }
        }
        return best;
    }

    /** {@code entity}'s box at {@code target} (nanoTime), blended between the samples either side of it. */
    private BoundingBox boxAt(LivingEntity entity, BoundingBox live, long target, long now) {
        Track track = tracks.get(entity.getUniqueId());
        if (track == null || track.count == 0 || !entity.getWorld().getUID().equals(track.world)) {
            return live;
        }
        // The newer end of the bracket starts at the live box, now.
        BoundingBox newer = live;
        long newerAt = now;
        for (int back = 0; back < track.count; back++) {
            BoundingBox older = track.box(back);
            long olderAt = track.time(back);
            if (olderAt <= target) {
                return blend(older, olderAt, newer, newerAt, target);
            }
            newer = older;
            newerAt = olderAt;
        }
        // Further back than the history goes: the oldest box there is.
        return newer;
    }

    private static BoundingBox blend(BoundingBox older, long olderAt, BoundingBox newer, long newerAt, long target) {
        if (newerAt <= olderAt) {
            return newer;
        }
        if (older.getCenter().distanceSquared(newer.getCenter()) > TELEPORT * TELEPORT) {
            // A teleport between the two: it was at one place or the other, never in between.
            return target - olderAt < newerAt - target ? older : newer;
        }
        double f = (double) (target - olderAt) / (newerAt - olderAt);
        return new BoundingBox(
                lerp(older.getMinX(), newer.getMinX(), f), lerp(older.getMinY(), newer.getMinY(), f), lerp(older.getMinZ(), newer.getMinZ(), f),
                lerp(older.getMaxX(), newer.getMaxX(), f), lerp(older.getMaxY(), newer.getMaxY(), f), lerp(older.getMaxZ(), newer.getMaxZ(), f));
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    /**
     * Carries a point on box {@code from} to the same place on box {@code to}, scaled per axis: the
     * height share and the offset from the middle that hit zones and limbs are read from stay the same,
     * also when the box changed size (crouching, lying down) in between.
     */
    static Vector carry(Vector point, BoundingBox from, BoundingBox to) {
        return new Vector(
                axis(point.getX(), from.getMinX(), from.getMaxX(), to.getMinX(), to.getMaxX()),
                axis(point.getY(), from.getMinY(), from.getMaxY(), to.getMinY(), to.getMaxY()),
                axis(point.getZ(), from.getMinZ(), from.getMaxZ(), to.getMinZ(), to.getMaxZ()));
    }

    private static double axis(double value, double fromMin, double fromMax, double toMin, double toMax) {
        double size = fromMax - fromMin;
        double share = size <= 1e-9 ? 0.5 : Math.max(0, Math.min(1, (value - fromMin) / size));
        return toMin + share * (toMax - toMin);
    }

    /** One entity's recent boxes, newest at {@code head}. */
    private static final class Track {
        private final long[] at = new long[SAMPLES];
        private final double[] boxes = new double[SAMPLES * 6];
        private int head;
        private int count;
        private int lastTick;
        private @Nullable UUID world;

        void add(World in, BoundingBox box, long now, int tick) {
            if (!in.getUID().equals(world)) {
                world = in.getUID();
                count = 0;
            }
            head = (head + 1) % SAMPLES;
            at[head] = now;
            int o = head * 6;
            boxes[o] = box.getMinX();
            boxes[o + 1] = box.getMinY();
            boxes[o + 2] = box.getMinZ();
            boxes[o + 3] = box.getMaxX();
            boxes[o + 4] = box.getMaxY();
            boxes[o + 5] = box.getMaxZ();
            count = Math.min(SAMPLES, count + 1);
            lastTick = tick;
        }

        /** The sample {@code back} steps before the newest (0 is the newest). */
        private int index(int back) {
            return Math.floorMod(head - back, SAMPLES);
        }

        long time(int back) {
            return at[index(back)];
        }

        BoundingBox box(int back) {
            int o = index(back) * 6;
            return new BoundingBox(boxes[o], boxes[o + 1], boxes[o + 2], boxes[o + 3], boxes[o + 4], boxes[o + 5]);
        }
    }
}
