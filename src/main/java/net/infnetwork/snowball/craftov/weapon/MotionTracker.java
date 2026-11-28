package net.infnetwork.snowball.craftov.weapon;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How fast each player is really moving, measured on the server from how far they get each tick.
 * Bukkit's velocity cannot tell: a player's own walking and running never write it (only knockback
 * and explosions do), so it reads about zero while they run across a street.
 *
 * <p>The ticks are the client's own (issue #155). A vanilla client ends every tick it runs with a
 * tick-end packet, sent after that tick's movement, so where the player is when one comes in is where
 * that tick of theirs left them: each is one step, however the packets fall into server ticks. A
 * server tick that brought none is no news rather than a stop: movement held back, a stalled
 * connection or a frame hitch never reads as standing still, and the server catching up on ticks
 * after a hitch of its own hands nobody a stop. A client that ends its ticks but keeps their movement
 * back reads as stopping, as it says it is, until the movement arrives: that tick then covers more
 * ground than vanilla physics allows from the speed the player had (see {@link #reach}), and the
 * player is judged carefully for a few seconds (see {@link #HELD_TICKS}). A client that sends no tick
 * ends (a bot, or a modified client that drops them) is measured per server tick as before, and
 * checked the same way, loosened by the ticks its packets may have been bunched over; so is one that
 * moves without the tick end a vanilla client sends right behind every move, from the next server
 * tick on, until its tick ends come back (issue #171: it could freeze its reading while it ran).
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
     * A step this many times the player's pace is more than one tick of movement: packets held back
     * and sent together (see {@link #average}). Sprinting is 1.3 times walking pace; a sprint-jump's
     * first tick goes further, but never right before a stop.
     */
    private static final double HELD_BACK = 1.5;
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
    /**
     * How many server ticks of paces are kept: the window is stretched by the player's round trip,
     * up to this, since the client keeps moving at the old speed until a lowered speed reaches it.
     */
    private static final int PACE_HISTORY = 80;
    /** Ticks of measured speed remembered to tell a stop from a steady walk (see {@link #stopping}). */
    private static final int STOP_WINDOW = 6;
    /**
     * A player slowing down is stopping once the measured speed is under this share of both their
     * speed of the last {@link #STOP_WINDOW} ticks and their walking pace now: a jitter in a steady
     * walk stays above it, and so does slowing down to the pace of aiming.
     */
    private static final double STOP_RATIO = 0.85;
    /**
     * Server ticks without a tick end after which a client that has not moved counts as one that sends
     * none. One that moves counts at once: a vanilla client sends a tick end right behind every move.
     */
    private static final int CLOCK_TIMEOUT = 40;
    /** What a tick on normal ground keeps of the player's speed (block friction 0.6 times 0.91). */
    private static final double GROUND_FRICTION = 0.546;
    /** What a tick in the air keeps of it. */
    private static final double AIR_FRICTION = 0.91;
    /** Most a tick in the air adds to it (sprinting). */
    private static final double AIR_ACCEL = 0.026;
    /** What a sprint-jump adds to it as the player leaves the ground. */
    private static final double JUMP_BOOST = 0.2;
    /** Blocks a tick allowed on top of the physics, for rounding and the client's own small pushes. */
    private static final double STEP_MARGIN = 0.02;
    /** Blocks a tick allowed for each other body the player stands in: the client pushes itself out of them. */
    private static final double CROWD_MARGIN = 0.05;
    /**
     * The speed (blocks a tick) a tick is checked from where the one before is not known: after a
     * teleport, a respawn or a change of world, and after a tick in water or lava, on a ladder, on ice,
     * riding or gliding, whose physics differ. About a sprint-jump's, carried as from the air, which
     * keeps the most of it: vanilla friction brings the bound down to the player's real speed within a
     * few ticks, with no ticks left unchecked to hold movement back in (issue #171).
     */
    private static final double CARRIED = 0.6;
    /** Server ticks a push from the server (a blast, a punch) may take to reach the client and be moved by. */
    private static final int PUSH_TICKS = 40;
    /** Client ticks of physics checked at most at once (a long stall's worth). */
    private static final int MAX_REACH = 60;
    /**
     * Server ticks after movement held back for which the player is judged carefully: as fast as they
     * have moved at any time since, and in the {@link #STOP_WINDOW} readings before, and never as
     * stopping. Holding back again, however long, buys nothing; doing it starts the 3 s over. A real
     * stop counts again once they are over.
     */
    private static final int HELD_TICKS = 60;

    private static final class Track {
        UUID world;
        /**
         * Where the last recorded tick ended: the client's last tick end, or for a client that sends
         * none the last server tick recorded. Steps are measured from here.
         */
        double x;
        double y;
        double z;
        /** No position to measure from yet (just joined, teleported, respawned or changed world). */
        boolean fresh = true;
        /** Horizontal distance covered in each of the last {@link #WINDOW} ticks. */
        final double[] steps = new double[WINDOW];
        int next;
        /** Steps recorded since the last reading. */
        int newSteps;
        /** The walking pace the movement-speed attribute allowed in each of the last server ticks. */
        final double[] paces = new double[PACE_HISTORY];
        /** The same without the sprint's part: what {@link #paces} become when the client stops a sprint itself. */
        final double[] walkPaces = new double[PACE_HISTORY];
        int nextPace;
        boolean pacesSet;
        /** The server tick the server last stopped the player's sprint itself (see {@link #sprintForced}). */
        long forcedStop = Long.MIN_VALUE / 2;
        /** The highest of {@link #paces}: the pace to compare with. */
        double pace = WALK_SPEED;
        /** Consecutive readings spent on the ground well over that pace. */
        int fastTicks;
        /** The measured speed ({@link #average}) of each of the last {@link #STOP_WINDOW} readings, and the newest. */
        final double[] speeds = new double[STOP_WINDOW];
        int nextSpeed;
        double speed;
        /** Slowing down to a stop at the last reading (see {@link #stopping}). */
        boolean stopping;
        /**
         * The speed the player is judged by: the measured one, while {@link #careful} the highest since
         * it began, and while {@link #clockless} about the highest of the last {@link #STOP_WINDOW} readings.
         */
        double judged;
        /** Judged carefully at the last reading, after movement held back (see {@link #HELD_TICKS}). */
        boolean careful;
        /** Server ticks of judging carefully left, and the highest speed read since it began. */
        int heldTicks;
        double heldSpeed;
        /** The server tick the last tick end came in. */
        long lastEnd = Long.MIN_VALUE / 2;
        /**
         * Measured per server tick: the client sends no tick ends, or moved without one after it (see
         * {@link #sample}), until its next tick end.
         */
        boolean clockless;
        /** Where the server had the player when the track last took a position (to see movement no tick end came for). */
        double seenX;
        double seenY;
        double seenZ;
        /** Measured per server tick: server ticks in a row that brought no movement. */
        int quiet;
        /** The last tick's step, as checked against the physics, and whether it started and ended on the ground. */
        double lastStep;
        boolean lastStartGround = true;
        boolean lastEndGround = true;
        /**
         * Server ticks left after a refused move in which a tick end is no news until the client's next
         * accepted move (see {@link #setBack}).
         */
        int awaitTicks;
        /** A push from the server that may still be arriving: its horizontal speed, and server ticks left. */
        double push;
        int pushTicks;
        /**
         * Where the client said it moved to in the movement packet of the tick now ending, when that
         * packet fired a move event (see {@link #claimed}).
         */
        double claimX;
        double claimY;
        double claimZ;
        boolean claimSet;
        /** Ground over the physics found since the last sample, and what the check saw, for the debug log. */
        double caughtSince;
        String caught = "";
    }

    private final Map<UUID, Track> tracks = new HashMap<>();

    /** A track that starts again from where the player is now. */
    private static void restart(Track track, Player player, Location at) {
        track.claimSet = false;
        Arrays.fill(track.steps, 0);
        Arrays.fill(track.speeds, 0);
        track.newSteps = 0;
        track.speed = 0;
        track.judged = 0;
        track.stopping = false;
        track.fastTicks = 0;
        track.fresh = false;
        track.world = at.getWorld().getUID();
        track.x = at.getX();
        track.y = at.getY();
        track.z = at.getZ();
        track.lastStep = CARRIED;
        track.lastStartGround = false;
        track.lastEndGround = grounded(player);
        // The server drops what the client sends until it has taken the teleport in: its tick ends
        // till then are no news, and the first move after starts from the speed carried.
        track.awaitTicks = settleTicks(player);
        track.quiet = 0;
        seen(track, player.getLocation());
    }

    /** Notes where the server has the player now that the track took a position. */
    private static void seen(Track track, Location server) {
        track.seenX = server.getX();
        track.seenY = server.getY();
        track.seenZ = server.getZ();
    }

    /** True when {@code at} is not where the track last took a position. */
    private static boolean away(Track track, Location at) {
        return at.getX() != track.x || at.getY() != track.y || at.getZ() != track.z;
    }

    /** True when the track has nothing to measure from: then it starts again here. */
    private static boolean restarted(Track track, Player player, Location at) {
        boolean elsewhere = !at.getWorld().getUID().equals(track.world);
        if (track.fresh || elsewhere) {
            if (elsewhere) {
                endCareful(track); // a raid's careful spell does not follow the player out of it
            }
            restart(track, player, at);
            return true;
        }
        return false;
    }

    /** Records one tick's step from the track's last position to {@code at}. */
    private static double step(Track track, Location at, int ticks) {
        double dx = at.getX() - track.x;
        double dz = at.getZ() - track.z;
        double moved = Math.sqrt(dx * dx + dz * dz);
        track.x = at.getX();
        track.y = at.getY();
        track.z = at.getZ();
        for (int i = 0; i < ticks; i++) {
            track.steps[track.next] = Math.min(MAX_STEP, moved / ticks);
            track.next = (track.next + 1) % WINDOW;
        }
        track.newSteps += ticks;
        return moved;
    }

    /**
     * The client ended a tick (Paper's ClientTickEndEvent, which comes after that tick's movement):
     * the player is where the tick left them, so this is one step of the client's own, measured and
     * checked against the physics here, one by one even when several arrive in one server tick.
     */
    void tickEnded(Player player) {
        Track track = tracks.computeIfAbsent(player.getUniqueId(), id -> new Track());
        track.lastEnd = Bukkit.getCurrentTick();
        Location server = player.getLocation();
        Location at = server.clone();
        if (track.claimSet) {
            at.set(track.claimX, track.claimY, track.claimZ);
            track.claimSet = false;
        } else if (track.awaitTicks > 0 && at.getX() == track.x && at.getY() == track.y && at.getZ() == track.z) {
            return; // put back and not moved since: the server drops what the client sends until it takes that in
        }
        track.awaitTicks = 0;
        if (restarted(track, player, at)) {
            track.clockless = false;
            return;
        }
        if (track.clockless) {
            // Back on the client's clock. Movement since the last server tick measured is checked as
            // those were; none is no news: that tick end belongs to movement already measured.
            track.clockless = false;
            if (away(track, at)) {
                double moved = step(track, at, 1);
                track.caughtSince = Math.max(track.caughtSince, check(player, track, moved, 1, track.quiet + 2));
            }
            track.quiet = 0;
            seen(track, server);
            return;
        }
        // The sprint it may have started this very tick (its command comes before the movement).
        track.pace = Math.max(track.pace, walkPace(player));
        double moved = step(track, at, 1);
        double caught = check(player, track, moved, 1, 1);
        track.caughtSince = Math.max(track.caughtSince, caught);
        seen(track, server);
    }

    /**
     * Takes a reading of how fast the player moves; call once per tick for every online player, after
     * the tick's packets. A client that sends tick ends has its steps recorded as they end (see
     * {@link #tickEnded}): a server tick without one is no news, and the reading stays as it was. One
     * that sends none is measured per server tick. Returns the ground (blocks) covered beyond what the
     * physics allows when movement held back arrived since the last reading (the player is judged
     * carefully from then on), else 0.
     */
    double sample(Player player) {
        Location at = player.getLocation();
        Track track = tracks.computeIfAbsent(player.getUniqueId(), id -> new Track());
        double walk = walkPace(player);
        double unsprinted = unsprinted(player);
        if (!track.pacesSet) {
            Arrays.fill(track.paces, walk);
            Arrays.fill(track.walkPaces, unsprinted);
            track.pacesSet = true;
        }
        track.paces[track.nextPace] = walk;
        track.walkPaces[track.nextPace] = unsprinted;
        track.nextPace = (track.nextPace + 1) % PACE_HISTORY;
        // A speed the server lowers (aiming on a sprint, stamina running out, a skill ending, a
        // fracture) reaches the client a round trip later, and until then it honestly moves at the
        // old pace: the window covers that round trip twice over (for jitter) on top of its own ticks.
        double pace = windowPace(track, player);
        track.pace = pace;
        if (track.heldTicks > 0) {
            track.heldTicks--;
        }
        if (track.pushTicks > 0) {
            track.pushTicks--;
        }
        if (track.awaitTicks > 0) {
            track.awaitTicks--;
        }
        if (restarted(track, player, at)) {
            return 0;
        }
        long now = Bukkit.getCurrentTick();
        if (!track.clockless && (at.getX() != track.seenX || at.getY() != track.seenY || at.getZ() != track.seenZ
                || now - track.lastEnd > CLOCK_TIMEOUT)) {
            // The player moved and no tick end came after it (or none has for 2 s): a client that stops
            // sending them would freeze its reading while it runs (issue #171). Measured per server
            // tick from here, starting with the movement since its last tick end, which may have taken
            // the server ticks since that end and one more (two packets in one server tick).
            track.clockless = true;
            track.quiet = (int) Math.max(0, Math.min(MAX_REACH, now - track.lastEnd) - 1);
        }
        if (track.clockless) {
            // One step per server tick. The physics may spend on movement that arrives the ticks since
            // the last that brought some (it may have been held back, or bunched up) and one more.
            if (track.claimSet) {
                at.set(track.claimX, track.claimY, track.claimZ);
                track.claimSet = false;
            }
            boolean arrived = away(track, at);
            double moved = step(track, at, 1);
            if (arrived) {
                track.caughtSince = Math.max(track.caughtSince, check(player, track, moved, 1, track.quiet + 2));
                track.quiet = 0;
            } else {
                track.quiet = Math.min(MAX_REACH, track.quiet + 1);
            }
            seen(track, player.getLocation());
        }
        double caught = track.caughtSince;
        track.caughtSince = 0;
        if (track.newSteps == 0) {
            return caught; // no news: nothing is known about this tick yet
        }
        track.newSteps = 0;
        double speed = average(track);
        double recent = 0;
        for (double s : track.speeds) {
            recent = Math.max(recent, s);
        }
        boolean careful = track.heldTicks > 0;
        if (careful) {
            track.heldSpeed = Math.max(track.heldSpeed, Math.max(speed, recent));
        }
        // Without the client's clock a stop cannot be told from movement held back (a client could
        // drop its tick ends for just that, issue #171): judged by the highest recent reading, never
        // as stopping. The second highest of them and this one: packets bunched up over server ticks
        // put a single reading well over the pace now and then, which would cost a steady sneak.
        double judged = careful ? track.heldSpeed : track.clockless ? secondHighest(track.speeds, speed) : speed;
        // A run ends as it reads: that only takes the sprint cone off, and a run kept on for the
        // readings after a sprint would hold it on a gun brought up out of one.
        boolean fast = groundRun(player) && (careful ? judged : speed) > RUN_RATIO * pace;
        track.fastTicks = fast ? track.fastTicks + 1 : 0;
        track.stopping = !careful && !track.clockless && speed < track.speed && speed < STOP_RATIO * Math.min(recent, walk);
        track.speed = speed;
        track.judged = judged;
        track.careful = careful;
        track.speeds[track.nextSpeed] = speed;
        track.nextSpeed = (track.nextSpeed + 1) % STOP_WINDOW;
        return caught;
    }

    /**
     * Checks {@code moved}, the ground covered in {@code ticks} ticks just recorded, against the most
     * vanilla physics allows in {@code reach} ticks (as many, or more where the client's ticks are not
     * known) from the speed of the tick before (see {@link #reach}). More than that is movement held
     * back and sent late: the player is judged carefully from now on. Returns the ground over it, or 0.
     */
    private double check(Player player, Track track, double moved, int ticks, int reach) {
        boolean ground = grounded(player);
        if (exempt(player)) {
            // Not vanilla ground physics: not checked, and the next tick starts from a speed it cannot
            // have beaten, from the air (whose friction keeps the most, more than ice's).
            track.lastStep = Math.max(CARRIED, moved);
            track.lastStartGround = false;
            track.lastEndGround = ground;
            return 0;
        }
        int n = Math.min(MAX_REACH, reach);
        double[] most = reach(n, track.lastStep, track.lastStartGround, track.lastEndGround, ground,
                track.pace / BLOCKS_PER_SPEED, track.pushTicks > 0 ? track.push : 0);
        double caught = 0;
        if (moved > most[0] + n * STEP_MARGIN && moved > most[0] + n * (STEP_MARGIN + crowd(player))) {
            caught = moved - most[0];
            if (track.heldTicks <= 0) {
                track.heldSpeed = 0;
            }
            track.heldTicks = HELD_TICKS;
            track.caught = String.format("moved=%.3f ticks=%d reach=%d most=%.3f last=%.3f ground=%s%s%s pace=%.3f push=%.2f",
                    moved, ticks, n, most[0], track.lastStep, track.lastStartGround ? "G" : "A", track.lastEndGround ? "G" : "A",
                    ground ? "G" : "A", track.pace, track.pushTicks > 0 ? track.push : 0);
        }
        // The speed the next tick starts from: this one's when it was one tick and allowed. A tick over
        // the physics was really moving at least at the player's pace (it was catching up), and
        // otherwise the most it can have been (and the air, which keeps the most, for where it started).
        if (n == 1 && caught == 0) {
            track.lastStep = moved;
        } else if (caught > 0) {
            track.lastStep = Math.max(most[1], Math.min(moved, track.pace));
        } else {
            track.lastStep = Math.min(moved, most[1]);
        }
        track.lastStartGround = n == 1 ? track.lastEndGround : false;
        track.lastEndGround = ground;
        return caught;
    }

    /**
     * The most ground {@code n} client ticks can cover, and the speed the last of them can end with, by
     * vanilla physics on normal ground: each tick moves the speed the last one left
     * ({@code v0} for the tick before these) times what that tick kept of it ({@link #GROUND_FRICTION}
     * if it started on the ground, {@link #AIR_FRICTION} in the air), plus what this tick adds: the
     * walking acceleration ({@code accel}, the movement-speed attribute, sprint included) on the
     * ground, {@link #AIR_ACCEL} in the air, and {@link #JUMP_BOOST} on a tick that leaves the ground.
     * The ticks start on the ground as {@code startGround} says and the last ends as {@code endGround}
     * says; in between every way of being on the ground or in the air counts (a jump, a fall, a
     * landing). A push from the server ({@code push}, its horizontal speed) may set the speed of the
     * first. Upper bounds throughout: a wall, a slower block or letting go of the keys only cover less.
     */
    static double[] reach(int n, double v0, boolean before, boolean startGround, boolean endGround, double accel, double push) {
        // Indexed by (started on the ground, the next tick starts on the ground) of the tick reached.
        double[] speed = new double[4];
        double[] ground = new double[4];
        Arrays.fill(speed, Double.NEGATIVE_INFINITY);
        Arrays.fill(ground, Double.NEGATIVE_INFINITY);
        int first = (before ? 2 : 0) | (startGround ? 1 : 0);
        speed[first] = v0;
        ground[first] = 0;
        for (int i = 1; i <= n; i++) {
            double[] nextSpeed = new double[4];
            double[] nextGround = new double[4];
            Arrays.fill(nextSpeed, Double.NEGATIVE_INFINITY);
            Arrays.fill(nextGround, Double.NEGATIVE_INFINITY);
            for (int prev = 0; prev < 4; prev++) {
                if (speed[prev] == Double.NEGATIVE_INFINITY) {
                    continue;
                }
                boolean prevOnGround = (prev & 2) != 0;
                boolean onGround = (prev & 1) != 0;
                for (int then = 0; then < 2; then++) {
                    boolean thenOnGround = then == 1;
                    if (i == n && thenOnGround != endGround) {
                        continue;
                    }
                    double add = (onGround ? accel : AIR_ACCEL) + (onGround && !thenOnGround ? JUMP_BOOST : 0);
                    double v = (prevOnGround ? GROUND_FRICTION : AIR_FRICTION) * speed[prev] + add;
                    if (i == 1 && push > 0) {
                        v = Math.max(v, push + add);
                    }
                    int k = (onGround ? 2 : 0) | then;
                    nextSpeed[k] = Math.max(nextSpeed[k], v);
                    nextGround[k] = Math.max(nextGround[k], ground[prev] + v);
                }
            }
            speed = nextSpeed;
            ground = nextGround;
        }
        double total = Double.NEGATIVE_INFINITY;
        double last = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < 4; k++) {
            total = Math.max(total, ground[k]);
            last = Math.max(last, speed[k]);
        }
        return new double[]{Math.max(0, total), Math.max(0, last)};
    }

    /**
     * Where the physics above does not hold: in water or lava, on a ladder, gliding or riding, flying,
     * on ice or slime, in powder snow. Such a tick is not checked, and the next is checked from
     * {@link #CARRIED}.
     */
    private static boolean exempt(Player player) {
        if (player.isInsideVehicle() || player.isFlying() || player.isGliding() || player.isRiptiding() || player.isSwimming()
                || player.isInWater() || player.isInLava() || player.isClimbing() || player.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }
        Location at = player.getLocation();
        Material below = underfoot(at);
        return at.getBlock().getType() == Material.POWDER_SNOW || below == Material.SLIME_BLOCK || below.name().contains("ICE");
    }

    /** Blocks a tick the client may push itself out of other bodies it stands in (see {@link #CROWD_MARGIN}). */
    private static double crowd(Player player) {
        BoundingBox body = player.getBoundingBox().expand(0.05);
        int bodies = 0;
        for (Entity other : player.getNearbyEntities(1, 1, 1)) {
            if (other instanceof LivingEntity && !(other instanceof ArmorStand) && other.getBoundingBox().overlaps(body) && ++bodies >= 4) {
                break;
            }
        }
        return bodies * CROWD_MARGIN;
    }

    /**
     * The client's movement packet moved it to {@code to} (a move event, which the server fires for a
     * move of more than a sixteenth of a block or a turn, before it applies the move). The server does
     * not always apply it: once after every teleport, the first move after the server turned the
     * player (a recoil kick) is dropped (CraftBukkit's justTeleported check), and the position catches
     * up with the next. Steps are measured from what the client said, so that one dropped move does
     * not read as a tick held back and then a tick covering two.
     */
    void claimed(Player player, Location to) {
        Track track = tracks.get(player.getUniqueId());
        if (track != null) {
            track.claimX = to.getX();
            track.claimY = to.getY();
            track.claimZ = to.getZ();
            track.claimSet = true;
        }
    }

    /**
     * The server refused a move and is putting the player back where they were (a move into a block,
     * or far too long; called as it refuses, while the player is still there): measured from there on,
     * from the speed of the last tick, since the client restarts from there with at most the speed it
     * had. Nothing goes unchecked for it, and the reading is kept: a client could get itself put back
     * at will (issue #171). Until its next accepted move (or a round trip and then some) its tick ends
     * are no news: the server drops its movement until it has taken the setback in, so they would read
     * as standing still.
     */
    void setBack(Player player) {
        Track track = tracks.get(player.getUniqueId());
        if (track != null && !track.fresh) {
            Location at = player.getLocation();
            track.x = at.getX();
            track.y = at.getY();
            track.z = at.getZ();
            track.claimSet = false;
            track.awaitTicks = settleTicks(player);
            seen(track, at);
        }
    }

    /**
     * Server ticks for something the server tells the client to reach it and come back: {@link
     * #PACE_WINDOW} and the player's round trip twice over (for jitter).
     */
    private static int settleTicks(Player player) {
        return Math.min(PACE_HISTORY, PACE_WINDOW + (int) Math.ceil(Math.max(0, player.getPing()) * 2 / 50.0));
    }

    /**
     * The server pushed the player ({@code velocity}, as sent): the client takes it on when the push
     * reaches it, so a tick in the next {@link #PUSH_TICKS} may start from that speed.
     */
    void pushed(Player player, Vector velocity) {
        Track track = tracks.get(player.getUniqueId());
        if (track != null) {
            double horizontal = Math.hypot(velocity.getX(), velocity.getZ());
            track.push = track.pushTicks > 0 ? Math.max(track.push, horizontal) : horizontal;
            track.pushTicks = PUSH_TICKS;
        }
    }

    /** What the last check that found movement held back saw, for the debug log. */
    String lastCaught(Player player) {
        Track track = tracks.get(player.getUniqueId());
        return track == null ? "" : track.caught;
    }

    /** True while the player is judged carefully after movement held back (see {@link #HELD_TICKS}). */
    boolean heldBack(Player player) {
        Track track = tracks.get(player.getUniqueId());
        return track != null && track.careful;
    }

    /**
     * Moving on foot over ground that gives normal grip: what can be compared with walking pace.
     * Ice and riding carry a player faster than their legs, and the air has its own pace.
     */
    private static boolean groundRun(Player player) {
        if (!grounded(player) || player.isInsideVehicle() || player.isFlying() || player.isGliding() || player.isSwimming()) {
            return false;
        }
        return !underfoot(player.getLocation()).name().contains("ICE");
    }

    /**
     * The block whose grip the player moves on, where vanilla looks for it: 0.500001 below the feet.
     * 0.2 found the slab, not the ice under it, for a player on a bottom slab laid on ice.
     */
    private static Material underfoot(Location feet) {
        return feet.clone().subtract(0, 0.500001, 0).getBlock().getType();
    }

    /**
     * Standing on something, by the server's own collision check under the feet. The client's on-ground
     * flag will not do: the vanilla client answers every camera turn the server sends (each recoil
     * kick) with a rotation packet that says it is off the ground, so a player firing full auto on
     * flat ground read as jumping on every other round; and a modified client could claim the ground
     * in mid-air.
     */
    static boolean grounded(Player player) {
        BoundingBox body = player.getBoundingBox();
        return player.getWorld().hasCollisionsIn(new BoundingBox(body.getMinX(), body.getMinY() - 0.06, body.getMinZ(),
                body.getMaxX(), body.getMinY(), body.getMaxZ()));
    }

    /**
     * True while the player has been running on the ground well over their walking pace for a few
     * ticks: sprinting, even if the client never said it started. By the careful speed while that
     * lasts (see {@link #HELD_TICKS}): held-back movement does not end a run either.
     */
    boolean running(Player player) {
        Track track = tracks.get(player.getUniqueId());
        return track != null && track.fastTicks >= RUN_TICKS;
    }

    /**
     * True while the player is coming to a stop: the measured speed fell this tick and is well under
     * both what it was in the last few ticks and the walking pace the player may go at now. That is
     * letting go of the keys or pressing the opposite one (a counter-strafe), not slowing down to aim
     * or under a heavier load, and not speeding up again after a turn the other way. Never while the
     * player is judged carefully after holding back movement (see {@link #HELD_TICKS}), nor while they
     * are measured without the client's clock.
     */
    boolean stopping(Player player) {
        Track track = tracks.get(player.getUniqueId());
        return track != null && track.stopping;
    }

    /**
     * Pace over the last {@link #WINDOW} ticks (the client's, or the server's for a client that sends
     * no tick ends), robust to how movement packets fall into server ticks without the client's: the
     * median of the three two-tick averages in it. A plain average reads a steady walk 25% slow
     * whenever one tick's packet arrives with the next tick's (steps x, x, x, 0 average 0.75x; their
     * two-tick averages x, x, x/2 have the median x), and such a tick is common. A stop shows within
     * two or three ticks: the median drops once two of the three pairs are small (a stop with the
     * opposite key, steps x, 0.1x, 0, 0, reads 0.05x; letting go, x, x, 0, 0 reads x/2 until the next
     * still tick). Movement held back and sent at once is the exception: a client that sends four
     * ticks of it together (steps 0, 0, 0, 4x) would read as standing on two ticks out of four, so
     * when one step in the window is longer than {@link #HELD_BACK} times the player's pace and the
     * median is at most half the plain average, the plain average counts (x on every tick of it).
     */
    private static double average(Track track) {
        double[] pairs = new double[WINDOW - 1];
        double sum = 0;
        double longest = 0;
        for (int i = 0; i < WINDOW - 1; i++) {
            pairs[i] = (track.steps[(track.next + i) % WINDOW] + track.steps[(track.next + i + 1) % WINDOW]) / 2;
        }
        for (double step : track.steps) {
            sum += step;
            longest = Math.max(longest, step);
        }
        Arrays.sort(pairs);
        double median = pairs[pairs.length / 2];
        double mean = sum / WINDOW;
        return longest > HELD_BACK * track.pace && median <= 0.5 * mean ? mean : median;
    }

    /** The second highest of {@code readings} and {@code now}. */
    private static double secondHighest(double[] readings, double now) {
        double first = now;
        double second = 0;
        for (double r : readings) {
            if (r > first) {
                second = first;
                first = r;
            } else if (r > second) {
                second = r;
            }
        }
        return second;
    }

    /** Blocks per tick the player's movement-speed attribute walks right now. */
    private static double walkPace(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        return (attribute == null ? 0.1 : attribute.getValue()) * BLOCKS_PER_SPEED;
    }

    /** The vanilla sprint's speed modifier (30% more, on the total). */
    private static final NamespacedKey SPRINTING = NamespacedKey.minecraft("sprinting");

    /** {@link #walkPace} without the sprint's part. */
    private static double unsprinted(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attribute == null) {
            return 0.1 * BLOCKS_PER_SPEED;
        }
        double value = attribute.getValue();
        AttributeModifier sprint = attribute.getModifier(SPRINTING);
        if (sprint != null && sprint.getOperation() == AttributeModifier.Operation.MULTIPLY_SCALAR_1 && sprint.getAmount() > -1) {
            value /= 1 + sprint.getAmount();
        }
        return value * BLOCKS_PER_SPEED;
    }

    /** The highest pace in the window the player's round trip calls for (see {@link #settleTicks}). */
    private static double windowPace(Track track, Player player) {
        int window = settleTicks(player);
        double pace = 0;
        for (int i = 1; i <= window; i++) {
            pace = Math.max(pace, track.paces[Math.floorMod(track.nextPace - i, PACE_HISTORY)]);
        }
        return pace;
    }

    /**
     * The server stopped the player's sprint itself (aiming, stamina, a broken leg, a slide, going
     * down): the client hears it a round trip later and runs at a sprint until then, so the sprint
     * stays in the pace to beat for the usual window, also when the client's own stop comes in.
     */
    void sprintForced(Player player) {
        Track track = tracks.get(player.getUniqueId());
        if (track != null) {
            track.forcedStop = Bukkit.getCurrentTick();
        }
    }

    /**
     * The client said it stopped sprinting (PlayerToggleSprintEvent, before its movement of that tick).
     * On its own word the sprint goes out of the pace to beat at once: it moves at its walking pace from
     * this tick on, and slows down by vanilla friction, which the check allows for. Kept for the usual
     * window was the gap of issue #171: a client that said it stopped but ran on at a sprint had the
     * walking cone for 10 ticks. Not when the server stopped the sprint lately (see {@link #sprintForced}).
     */
    void sprintStopped(Player player) {
        Track track = tracks.get(player.getUniqueId());
        if (track == null || !track.pacesSet || Bukkit.getCurrentTick() - track.forcedStop <= settleTicks(player)) {
            return;
        }
        System.arraycopy(track.walkPaces, 0, track.paces, 0, PACE_HISTORY);
        track.pace = windowPace(track, player);
    }

    /** Horizontal speed in blocks per tick, averaged over the last few ticks. */
    double speed(Player player) {
        Track track = tracks.get(player.getUniqueId());
        return track == null ? 0 : average(track);
    }

    /**
     * How close the player is to their full walking pace, 0 (still) to 1. Measured against the pace their
     * movement-speed attribute allows right now, so a player slowed by aiming, armour or a broken leg who
     * walks as fast as they can counts as fully moving. By the careful speed for a while after movement
     * held back (see {@link #HELD_TICKS}).
     */
    double moveFactor(Player player) {
        Track track = tracks.get(player.getUniqueId());
        double speed = track == null ? 0 : track.judged;
        return Math.min(1.0, speed / Math.max(0.06, walkPace(player)));
    }

    /**
     * Forgets the last position: the next tick measures from wherever the player lands. A move the
     * client sent before the teleport goes too, or the tick end after it would start the track from
     * where the player was before.
     */
    void reset(Player player) {
        Track track = tracks.get(player.getUniqueId());
        if (track != null) {
            track.fresh = true;
            track.claimSet = false;
        }
    }

    /** Respawned: a fresh track, and judged carefully no more (see {@link #HELD_TICKS}). */
    void respawned(Player player) {
        reset(player);
        Track track = tracks.get(player.getUniqueId());
        if (track != null) {
            endCareful(track);
        }
    }

    private static void endCareful(Track track) {
        track.heldTicks = 0;
        track.heldSpeed = 0;
        track.careful = false;
    }

    void forget(UUID player) {
        tracks.remove(player);
    }
}
