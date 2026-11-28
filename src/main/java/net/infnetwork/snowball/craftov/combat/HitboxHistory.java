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
 * <p>Every tick this records the hitbox of every living thing in a raid world and on the range (the
 * last {@link #SAMPLES} ticks, with the time each was taken), and a player's shot is tested against the boxes
 * as they were {@code ping + combat.lag-compensation.extra-ms} ago, at most {@code max-ms}, blended
 * between the two samples either side. The ping Paper reports is the round trip, which already covers
 * both legs; the extra (200 ms by default) covers the client's smoothing, which trails the updates by
 * its 3 steps whether they come every 2 ticks or 3, how far the server has moved the target on since
 * the last update it sent, drawing between ticks, and the shot waiting for the next tick (rounds are
 * fired from a tick task). A bot that draws targets as a vanilla client does (tools/bot/hitreg.js)
 * finds them 165 to 185 ms behind the server with no network delay. Blocks are not rewound,
 * so a target that has just stepped behind cover can still be hit for up to max-ms. Soldiers shoot at
 * the present (their aim is computed on the server).
 *
 * <p>A sliding player (SlideService) sits low with the legs stretched out ahead, outside the box: the
 * legs are a box of their own along the ground ({@link SlideService.Legs}), recorded and rewound with
 * the body, and a round that meets them first is a leg hit. Others are sent only the seat, every tick
 * (a player every 2), and their clients ease into each update over 2 ticks (the seat's teleport
 * duration), so they draw a slider closer behind the server than anyone running: tools/bot/slide.js
 * (cross) finds the seat drawn about 100 ms behind, and its rounds at a slider crossing the view hit
 * with any rewind from about 85 to 205 ms (with no network delay). A sliding target is therefore
 * rewound by {@code ping + combat.lag-compensation.slide-extra-ms} (150 by default) instead, also just
 * after the slide while that rewind still lands in it. Its box is recorded on the seat
 * (SlideService#hitbox), which this tick's updates send, not where the rider is until the world tick
 * moves it there: a tick behind, which made a rewind look 50 ms further back than it meant to.
 */
public final class HitboxHistory {

    /** Samples kept per entity, one a tick: past the longest rewind allowed. */
    static final int SAMPLES = 24;
    /** The longest rewind, whatever max-ms says: the history kept covers no more. */
    static final int LONGEST_MS = 1000;
    /** Farther than this between two samples is a teleport, not movement to blend across. */
    private static final double TELEPORT = 4.0;
    /**
     * Blocks a second that a rewound box is looked for beyond the ray's own reach, by where the entity
     * is now: past a sprint-jump (about 7) with some speed to spare.
     */
    private static final double REACH_PER_SECOND = 10.0;

    private final CraftovPlugin plugin;
    private final Map<UUID, Track> tracks = new HashMap<>();

    public HitboxHistory(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::record, 1L, 1L);
    }

    /**
     * How far back a player's shots look, in milliseconds: {@code millis} at everyone, {@code slideMillis}
     * at a sliding player (see the class comment). {@link #NONE} for soldiers and with compensation off.
     */
    public record Rewind(int millis, int slideMillis) {
        public static final Rewind NONE = new Rewind(0, 0);

        int longest() {
            return Math.max(millis, slideMillis);
        }
    }

    /**
     * A round meeting an entity's box as the shooter saw it.
     *
     * @param point     where the round met the (rewound) box: where the shooter saw it land
     * @param box       that box, which the hit zone is read from
     *                  ({@link BodyPart#of(LivingEntity, BoundingBox, double, Vector, Vector)})
     * @param eyeHeight how high above its feet the entity's eyes were then, which sets the head line
     * @param body      the same spot on the entity's box as it stands now, where the wound shows
     * @param distance  from the start of the ray to {@code point}
     * @param part      the body part when the box does not tell it: a sliding player's legs (else null)
     * @param rewind    how far back the entity was looked for, in milliseconds
     */
    public record Hit(LivingEntity entity, Vector point, BoundingBox box, double eyeHeight, Vector body, double distance,
                      @Nullable BodyPart part, int rewind) {
    }

    /** An entity's box, eye height, (sliding) legs and whether it slid, at one moment. */
    private record Pose(BoundingBox box, double eyeHeight, SlideService.@Nullable Legs legs, boolean sliding) {
    }

    /** {@code entity}'s pose now, a sliding player's on the seat (SlideService#hitbox). */
    private Pose pose(LivingEntity entity) {
        BoundingBox seated = plugin.slides().hitbox(entity);
        return new Pose(seated != null ? seated : entity.getBoundingBox(), entity.getEyeHeight(), plugin.slides().legs(entity), seated != null);
    }

    private void record() {
        long now = System.nanoTime();
        int tick = Bukkit.getCurrentTick();
        for (Raid raid : plugin.raids().raids()) {
            record(raid.world, now, tick);
        }
        // The range's moving targets are drawn behind where they are, as a soldier is.
        World range = plugin.range().world();
        if (range != null) {
            record(range, now, tick);
        }
        if (tick % 20 == 0) {
            tracks.values().removeIf(track -> tick - track.lastTick > SAMPLES);
        }
    }

    private void record(World world, long now, int tick) {
        for (LivingEntity entity : world.getLivingEntities()) {
            if (entity instanceof ArmorStand || entity.isDead()) {
                continue;
            }
            tracks.computeIfAbsent(entity.getUniqueId(), id -> new Track()).add(world, pose(entity), now, tick);
        }
    }

    /**
     * How far back {@code shooter}'s shots look: {@code ping + extra-ms} (at a sliding player
     * {@code ping + slide-extra-ms}), at most {@code max-ms} (and at most {@link #LONGEST_MS}).
     * {@link Rewind#NONE} for soldiers and with compensation off.
     */
    public Rewind rewind(LivingEntity shooter) {
        if (!(shooter instanceof Player player) || !plugin.getConfig().getBoolean("combat.lag-compensation.enabled", true)) {
            return Rewind.NONE;
        }
        int max = Math.max(0, Math.min(LONGEST_MS, plugin.getConfig().getInt("combat.lag-compensation.max-ms", 300)));
        int extra = plugin.getConfig().getInt("combat.lag-compensation.extra-ms", 200);
        int slideExtra = plugin.getConfig().getInt("combat.lag-compensation.slide-extra-ms", 150);
        int ping = player.getPing();
        return new Rewind(Math.max(0, Math.min(max, ping + extra)), Math.max(0, Math.min(max, ping + slideExtra)));
    }

    /**
     * The nearest entity passing {@code filter} that a ray from {@code from} along {@code dir} meets
     * within {@code maxDistance}, each tested by its box as far back as {@code rewind} says (now when 0).
     */
    public @Nullable Hit trace(World world, Vector from, Vector dir, double maxDistance, Predicate<Entity> filter, Rewind rewind) {
        if (maxDistance <= 0) {
            return null;
        }
        Vector direction = dir.clone().normalize();
        long now = System.nanoTime();
        BoundingBox span = BoundingBox.of(from, from).expandDirectional(direction.clone().multiply(maxDistance));
        if (rewind.longest() > 0) {
            // Whoever the shooter saw on the line may have moved off it since: look as far around it as
            // anyone can run in the rewind.
            span.expand(1.0 + REACH_PER_SECOND * rewind.longest() / 1000.0);
        } else {
            // A sliding body's legs reach ahead of its box (SlideService.LEGS_TO).
            span.expand(SlideService.LEGS_TO);
        }
        Hit best = null;
        for (Entity entity : world.getNearbyEntities(span, filter)) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            Pose live = pose(living);
            Pose seen = live;
            int back = 0;
            if (rewind.longest() > 0) {
                // A slider as its seat is drawn (see the class comment): while it slides, and after it
                // as long as the shorter rewind still lands in the slide.
                Pose slid = poseAt(living, live, now - rewind.slideMillis() * 1_000_000L, now);
                boolean slider = live.sliding() || slid.sliding();
                back = slider ? rewind.slideMillis() : rewind.millis();
                seen = slider ? slid : poseAt(living, live, now - back * 1_000_000L, now);
            }
            RayTraceResult result = seen.box().rayTrace(from, direction, maxDistance);
            if (result != null) {
                Vector point = result.getHitPosition();
                double distance = from.distance(point);
                if (best == null || distance < best.distance()) {
                    best = new Hit(living, point, seen.box(), seen.eyeHeight(), carry(point, seen.box(), live.box()), distance, null, back);
                }
            }
            if (seen.legs() != null) {
                double along = seen.legs().trace(from, direction, maxDistance);
                if (along >= 0 && (best == null || along < best.distance())) {
                    Vector point = from.clone().add(direction.clone().multiply(along));
                    // The wound shows on the legs where they are now (or on the body, the slide over).
                    SlideService.Legs now2 = live.legs();
                    Vector body = now2 == null ? carry(point, seen.box(), live.box())
                            : point.clone().add(new Vector(now2.x() - seen.legs().x(), now2.y() - seen.legs().y(), now2.z() - seen.legs().z()));
                    best = new Hit(living, point, seen.box(), seen.eyeHeight(), body, along, seen.legs().part(point), back);
                }
            }
        }
        return best;
    }

    /** {@code entity}'s box and eyes at {@code target} (nanoTime), blended between the samples either side of it. */
    private Pose poseAt(LivingEntity entity, Pose live, long target, long now) {
        Track track = tracks.get(entity.getUniqueId());
        if (track == null || track.count == 0 || !entity.getWorld().getUID().equals(track.world)) {
            return live;
        }
        // The newer end of the bracket starts at the live box, now.
        Pose newer = live;
        long newerAt = now;
        for (int back = 0; back < track.count; back++) {
            Pose older = track.pose(back);
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

    private static Pose blend(Pose olderPose, long olderAt, Pose newerPose, long newerAt, long target) {
        if (newerAt <= olderAt) {
            return newerPose;
        }
        BoundingBox older = olderPose.box();
        BoundingBox newer = newerPose.box();
        if (older.getCenter().distanceSquared(newer.getCenter()) > TELEPORT * TELEPORT) {
            // A teleport between the two: it was at one place or the other, never in between.
            return target - olderAt < newerAt - target ? olderPose : newerPose;
        }
        double f = (double) (target - olderAt) / (newerAt - olderAt);
        // Legs only while sliding: blended when both ends have them, else those of the nearer end.
        SlideService.Legs legs;
        if (olderPose.legs() != null && newerPose.legs() != null) {
            legs = olderPose.legs().lerp(newerPose.legs(), f);
        } else {
            legs = f < 0.5 ? olderPose.legs() : newerPose.legs();
        }
        return new Pose(new BoundingBox(
                lerp(older.getMinX(), newer.getMinX(), f), lerp(older.getMinY(), newer.getMinY(), f), lerp(older.getMinZ(), newer.getMinZ(), f),
                lerp(older.getMaxX(), newer.getMaxX(), f), lerp(older.getMaxY(), newer.getMaxY(), f), lerp(older.getMaxZ(), newer.getMaxZ(), f)),
                lerp(olderPose.eyeHeight(), newerPose.eyeHeight(), f), legs, f < 0.5 ? olderPose.sliding() : newerPose.sliding());
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    /**
     * Carries a point on box {@code from} to the same place on box {@code to}, scaled per axis, so the
     * wound and the blood show on the body where it stands now, also when the box changed size
     * (crouching, lying down) in between. Only for showing: the hit zone is read on {@code from}.
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

    /** One entity's recent boxes, eye heights, legs and slides, newest at {@code head}. */
    private static final class Track {
        private final long[] at = new long[SAMPLES];
        private final double[] boxes = new double[SAMPLES * 6];
        private final double[] eyes = new double[SAMPLES];
        private final SlideService.@Nullable Legs[] legs = new SlideService.Legs[SAMPLES];
        private final boolean[] sliding = new boolean[SAMPLES];
        private int head;
        private int count;
        private int lastTick;
        private @Nullable UUID world;

        void add(World in, Pose pose, long now, int tick) {
            if (!in.getUID().equals(world)) {
                world = in.getUID();
                count = 0;
            }
            head = (head + 1) % SAMPLES;
            at[head] = now;
            BoundingBox box = pose.box();
            int o = head * 6;
            boxes[o] = box.getMinX();
            boxes[o + 1] = box.getMinY();
            boxes[o + 2] = box.getMinZ();
            boxes[o + 3] = box.getMaxX();
            boxes[o + 4] = box.getMaxY();
            boxes[o + 5] = box.getMaxZ();
            eyes[head] = pose.eyeHeight();
            legs[head] = pose.legs();
            sliding[head] = pose.sliding();
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

        Pose pose(int back) {
            int i = index(back);
            int o = i * 6;
            return new Pose(new BoundingBox(boxes[o], boxes[o + 1], boxes[o + 2], boxes[o + 3], boxes[o + 4], boxes[o + 5]), eyes[i], legs[i], sliding[i]);
        }
    }
}
