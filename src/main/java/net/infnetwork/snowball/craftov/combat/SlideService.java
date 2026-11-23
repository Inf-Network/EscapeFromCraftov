package net.infnetwork.snowball.craftov.combat;

import io.papermc.paper.entity.TeleportFlag;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.util.Notify;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Slide: sprinting forward, two quick taps of the sneak key (the second within slide.double-tap-ms
 * of the first, the first let go within slide.first-hold-ms) drop the player low and carry them on
 * about 4 blocks in 0.8 s, at twice a sprint's stamina (slide.stamina-multiplier). A and D steer a
 * little. Holding sneak to walk silently, sneaking along, or a double tap while walking never slides:
 * only a sprint does (sprinting at the first tap, or within slide.sprint-grace-ms before it, since
 * the client may end the sprint as sneak goes down). Then slide.cooldown-ticks, and at least
 * slide.sprint-ticks of sprinting, before the next one, so sliding never covers ground faster than
 * running does. Only in a raid and on the private range.
 *
 * <p>The vanilla client cannot be pushed along the ground smoothly or made to lower its body, so the
 * player sits on an invisible item display (a seat, never saved) that the server moves every tick:
 * a rider's feet are 0.6 below the seat, so on a seat at ground level the eyes are 1.02 up, others
 * see the player sitting (legs forward, the gun up: StanceService) and their hitbox shows 1.2 m of
 * itself above the ground (head 0.7 to 1.2, torso down to 0.12, by HitZone's split of the standing
 * box, which is held: the pose is fixed standing, so sneak cannot shrink it), with the legs stretched
 * out in front as a box of their own ({@link #legs}, HitboxHistory). The seat follows the ground: up
 * steps of up to slide.step-up, down drops of up to slide.max-drop; a wall or a closed door stops the
 * slide at once, and a deeper drop ends it with the speed handed back, so the player falls on. Jump
 * ends it too, with the speed kept: the client jumps as it lands on its feet.
 *
 * <p>Sneak is not sneaking while sliding: the second tap is usually still held, and vanilla dismounts
 * a rider whose sneak key is down. The server's sneak state is cleared every tick before the riders
 * are moved (this runs first in the tick), so the player stays seated and nothing that reads sneaking
 * (the silent walk, sneak+Q, sneak+F, the quieter lob, the steadier aim) applies; the callers check
 * {@link #isSliding} as well. Any other dismount (a teleport, an extraction, death) is let through
 * and ends the slide: the rider is put down on the seat, at ground level.
 */
public final class SlideService implements Listener {

    /** How far below its seat a rider's feet are (vanilla's player vehicle attachment). */
    public static final double RIDE_DROP = 0.6;
    /** The legs stretched out in front of a sliding body: from, to (ahead of its centre), half its width and height. */
    public static final double LEGS_FROM = 0.2;
    public static final double LEGS_TO = 1.0;
    public static final double LEGS_HALF_WIDTH = 0.3;
    public static final double LEGS_HEIGHT = 0.3;
    /** Ticks of positions kept per player, to measure speed and direction at the second tap. */
    private static final int HISTORY = 8;
    /** Half the width of the sliding body, and its height above the ground, for collisions. */
    private static final double HALF = 0.3;
    private static final double BODY = 1.2;
    private static final String SEAT = "slide_seat";

    /**
     * The legs of a sliding player as the server has them: a box along the ground ahead of the body,
     * {@code (x, z)} the body's centre on the ground at {@code y}, {@code (dirX, dirZ)} the way it slides.
     */
    public record Legs(double x, double y, double z, double dirX, double dirZ) {

        /** Where a ray from {@code from} along unit {@code dir} first meets the legs, within {@code max}, or -1. */
        public double trace(Vector from, Vector dir, double max) {
            double rx = -dirZ;
            double rz = dirX;
            double ox = from.getX() - x;
            double oz = from.getZ() - z;
            // The ray in the legs' own frame: along the slide, across it, and up from the ground.
            double[] origin = {ox * dirX + oz * dirZ, ox * rx + oz * rz, from.getY() - y};
            double[] step = {dir.getX() * dirX + dir.getZ() * dirZ, dir.getX() * rx + dir.getZ() * rz, dir.getY()};
            double[] min = {LEGS_FROM, -LEGS_HALF_WIDTH, 0};
            double[] max2 = {LEGS_TO, LEGS_HALF_WIDTH, LEGS_HEIGHT};
            double enter = 0;
            double leave = max;
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(step[axis]) < 1e-9) {
                    if (origin[axis] < min[axis] || origin[axis] > max2[axis]) {
                        return -1;
                    }
                    continue;
                }
                double t1 = (min[axis] - origin[axis]) / step[axis];
                double t2 = (max2[axis] - origin[axis]) / step[axis];
                enter = Math.max(enter, Math.min(t1, t2));
                leave = Math.min(leave, Math.max(t1, t2));
                if (enter > leave) {
                    return -1;
                }
            }
            return enter;
        }

        /** The left or right leg for a hit at {@code point}: the side of the slide line it lands on. */
        public BodyPart part(Vector point) {
            double side = (point.getX() - x) * -dirZ + (point.getZ() - z) * dirX;
            return side >= 0 ? BodyPart.RIGHT_LEG : BodyPart.LEFT_LEG;
        }

        /** These legs moved to another body centre and ground (the same slide a moment later or earlier). */
        public Legs at(double x2, double y2, double z2) {
            return new Legs(x2, y2, z2, dirX, dirZ);
        }

        /** Between {@code this} and {@code other}, {@code f} of the way. */
        public Legs lerp(Legs other, double f) {
            double dx = dirX + (other.dirX - dirX) * f;
            double dz = dirZ + (other.dirZ - dirZ) * f;
            double length = Math.hypot(dx, dz);
            if (length < 1e-6) {
                dx = other.dirX;
                dz = other.dirZ;
                length = 1;
            }
            return new Legs(x + (other.x - x) * f, y + (other.y - y) * f, z + (other.z - z) * f, dx / length, dz / length);
        }
    }

    /** How a finished slide went, for /cv slide and the bot tests. */
    public record Summary(long endTick, String reason, int ticks, double distance, double spent,
                          double startX, double startY, double startZ, double endX, double endY, double endZ,
                          double minYLow, double minYHigh, double maxYLow, double maxYHigh, double startSpeed) {
    }

    /** One player's keys, sprint and recent positions. */
    private static final class State {
        /** nanoTime of the first tap going down and coming up, 0 when there is none. */
        long firstDown;
        long firstUp;
        boolean sprintAtFirst;
        /** nanoTime the player was last seen sprinting. */
        long sprintSeen;
        /** A double tap passed every check: the slide starts on the next tick. */
        boolean pending;
        long endedTick = -1000;
        /** Ticks spent sprinting since the last slide ended. */
        int sprintTicks = Integer.MAX_VALUE / 2;
        final double[] xs = new double[HISTORY];
        final double[] zs = new double[HISTORY];
        int next;
        int count;
        @Nullable UUID world;
        @Nullable String refused;
        long refusedTick;
        @Nullable Summary last;
    }

    /** A slide under way. */
    private static final class Slide {
        final Player player;
        final ItemDisplay seat;
        double x;
        double y;
        double z;
        double yaw;
        double speed;
        final double startSpeed;
        int ticks;
        final double startX;
        final double startY;
        final double startZ;
        final double staminaBefore;
        /** The lowest and highest the body's box reached, minus the ground under it. */
        double bottomLow = Double.MAX_VALUE;
        double bottomHigh = -Double.MAX_VALUE;
        double topLow = Double.MAX_VALUE;
        double topHigh = -Double.MAX_VALUE;

        Slide(Player player, ItemDisplay seat, double x, double y, double z, double yaw, double speed, double stamina) {
            this.player = player;
            this.seat = seat;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.speed = speed;
            this.startSpeed = speed;
            this.startX = x;
            this.startY = y;
            this.startZ = z;
            this.staminaBefore = stamina;
        }

        double dirX() {
            return -Math.sin(Math.toRadians(yaw));
        }

        double dirZ() {
            return Math.cos(Math.toRadians(yaw));
        }
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Slide> slides = new HashMap<>();
    /** Set while this service takes a rider off its seat, so the dismount is known to be its own. */
    private boolean releasing;

    public SlideService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private State state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), k -> new State());
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    private double setting(String key, double fallback) {
        return config().getDouble("slide." + key, fallback);
    }

    // ------------------------------------------------------------------ queries

    public boolean isSliding(Entity entity) {
        return slides.containsKey(entity.getUniqueId());
    }

    /**
     * Where the player stands: their location, or while they slide the point on the ground under
     * them (the body itself is {@link #RIDE_DROP} below it). For whatever is put where a player is: a
     * body, a stand-in, a rejoin, a replay.
     */
    public Location feet(Player player) {
        Location at = player.getLocation();
        Slide slide = slides.get(player.getUniqueId());
        if (slide != null) {
            at.add(0, RIDE_DROP, 0);
        }
        return at;
    }

    /** The legs of a sliding player, stretched out ahead of the body; null while not sliding. */
    public @Nullable Legs legs(LivingEntity entity) {
        Slide slide = slides.get(entity.getUniqueId());
        if (slide == null || !config().getBoolean("slide.leg-hitbox", true)) {
            return null;
        }
        Location at = entity.getLocation();
        return new Legs(at.getX(), at.getY() + RIDE_DROP, at.getZ(), slide.dirX(), slide.dirZ());
    }

    public @Nullable Summary last(Player player) {
        State s = states.get(player.getUniqueId());
        return s == null ? null : s.last;
    }

    /** Why the player's last double tap did not slide, and how many ticks ago; null when none did not. */
    public @Nullable String refused(Player player) {
        State s = states.get(player.getUniqueId());
        return s == null || s.refused == null ? null : s.refused + " ago=" + (Bukkit.getCurrentTick() - s.refusedTick);
    }

    /** Ticks until the player may slide again (0 when they may). */
    public int cooldownLeft(Player player) {
        State s = states.get(player.getUniqueId());
        if (s == null) {
            return 0;
        }
        long left = s.endedTick + Math.max(0, (long) setting("cooldown-ticks", 30)) - Bukkit.getCurrentTick();
        return (int) Math.max(0, left);
    }

    /** Ticks of sprinting since the last slide ended. */
    public int sprintTicks(Player player) {
        State s = states.get(player.getUniqueId());
        return s == null ? Integer.MAX_VALUE / 2 : s.sprintTicks;
    }

    // ------------------------------------------------------------------ the double tap

    /**
     * Sneak going down or up. Read here with the input from before it ({@link Player#getCurrentInput})
     * to tell the edges; times are when the packets came in, so network jitter moves them by a few
     * tens of milliseconds (the windows are in config.yml).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        Input now = event.getInput();
        Input before = player.getCurrentInput();
        boolean press = now.isSneak() && !before.isSneak();
        boolean release = !now.isSneak() && before.isSneak();
        if ((!press && !release) || isSliding(player)) {
            return;
        }
        State s = state(player);
        long t = System.nanoTime();
        if (release) {
            if (s.firstDown != 0 && s.firstUp == 0) {
                s.firstUp = t;
            }
            return;
        }
        long window = (long) (setting("double-tap-ms", 300) * 1_000_000L);
        long hold = (long) (setting("first-hold-ms", 200) * 1_000_000L);
        if (s.firstDown != 0 && s.firstUp != 0 && t - s.firstDown <= window && s.firstUp - s.firstDown <= hold) {
            boolean sprinted = s.sprintAtFirst;
            s.firstDown = 0;
            s.firstUp = 0;
            if (!sprinted) {
                refuse(player, s, "第一下按下时没在冲刺", false);
                return;
            }
            String why = blocker(player, s, now);
            if (why == null) {
                s.pending = true;
                s.refused = null;
            } else {
                refuse(player, s, why, true);
            }
            return;
        }
        s.firstDown = t;
        s.firstUp = 0;
        long grace = (long) (setting("sprint-grace-ms", 150) * 1_000_000L);
        s.sprintAtFirst = player.isSprinting() || s.sprintSeen != 0 && t - s.sprintSeen <= grace;
    }

    /**
     * Notes why a double tap did not slide. {@code tell}: the player meant to slide (sprinting into it)
     * and is told why not, when it is something they can wait out.
     */
    private void refuse(Player player, State s, String why, boolean tell) {
        s.refused = why;
        s.refusedTick = Bukkit.getCurrentTick();
        if (tell && (why.startsWith("体力") || why.startsWith("滑铲冷却"))) {
            Notify.hint(player, why);
        }
        if (config().getBoolean("debug", false)) {
            plugin.getLogger().info("[debug] slide " + player.getName() + " refused: " + why);
        }
    }

    /** Why the player may not start a slide right now (at the second tap), or null. */
    private @Nullable String blocker(Player player, State s, Input input) {
        if (!config().getBoolean("slide.enabled", true)) {
            return "滑铲已关闭";
        }
        if (!allowed(player)) {
            return "只能在行动中和靶场滑铲";
        }
        if (plugin.downed().isDowned(player) || plugin.downed().isReviving(player)) {
            return "倒地或救援时不能滑铲";
        }
        if (player.isInsideVehicle() || player.isGliding() || player.isFlying() || player.isSwimming()
                || player.isInWater() || player.isInLava() || player.isClimbing() || player.isSleeping()) {
            return "现在不能滑铲";
        }
        if (plugin.health().medUse(player) != null) {
            return "用药时不能滑铲";
        }
        if (plugin.weapons().isAiming(player)) {
            return "开镜时不能滑铲";
        }
        if (!input.isForward() || input.isBackward()) {
            return "没有按住前进";
        }
        if (!grounded(player)) {
            return "不在地面上";
        }
        double speed = recentSpeed(s);
        if (speed < setting("min-speed", 0.2)) {
            return "速度不够（" + String.format("%.2f", speed) + " 格/tick）";
        }
        int left = cooldownLeft(player);
        if (left > 0) {
            return "滑铲冷却中（" + String.format("%.1f", left / 20.0) + " 秒）";
        }
        int sprinted = s.sprintTicks;
        int need = (int) setting("sprint-ticks", 10);
        if (sprinted < need) {
            return "滑铲冷却中：先冲刺一会儿";
        }
        if (plugin.health().sprintBlocked(player)) {
            return plugin.stamina().exhausted(player) ? "体力耗尽，无法滑铲" : "现在不能冲刺，也不能滑铲";
        }
        if (plugin.raids().isInRaid(player) && plugin.stamina().stamina(player) < setting("min-stamina", 15)) {
            return "体力不足，无法滑铲";
        }
        return null;
    }

    /** In a raid (alive and able to act) or on the private range, and playing (not a spectator). */
    private boolean allowed(Player player) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.SPECTATOR || mode == GameMode.CREATIVE || player.isDead()) {
            return false;
        }
        return plugin.range().isIn(player) || plugin.raids().isInRaid(player) && plugin.raids().canFire(player);
    }

    /** Standing on something, by the server's own collision check under the feet (as MotionTracker does). */
    private static boolean grounded(Player player) {
        BoundingBox body = player.getBoundingBox();
        return player.getWorld().hasCollisionsIn(new BoundingBox(body.getMinX(), body.getMinY() - 0.06, body.getMinZ(),
                body.getMaxX(), body.getMinY(), body.getMaxZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent event) {
        if (!event.isSprinting() && event.getPlayer().isSprinting()) {
            // The sprint ends now: the first tap may come with it, or just after.
            state(event.getPlayer()).sprintSeen = System.nanoTime();
        }
    }

    // ------------------------------------------------------------------ positions

    private void sample(Player player, State s) {
        Location at = player.getLocation();
        UUID world = at.getWorld().getUID();
        if (!world.equals(s.world)) {
            s.world = world;
            s.count = 0;
        }
        s.xs[s.next] = at.getX();
        s.zs[s.next] = at.getZ();
        s.next = (s.next + 1) % HISTORY;
        s.count = Math.min(HISTORY, s.count + 1);
    }

    /** The position {@code back} ticks before the newest sample (0 is the newest). */
    private static double[] at(State s, int back) {
        int i = Math.floorMod(s.next - 1 - back, HISTORY);
        return new double[]{s.xs[i], s.zs[i]};
    }

    /**
     * How fast the player has lately been moving, in blocks a tick: the fastest of the two-tick
     * averages over the last ticks (movement packets sometimes arrive two in one tick). The fastest,
     * since the first tap already slows a sprint down.
     */
    private static double recentSpeed(State s) {
        double best = 0;
        for (int back = 0; back + 2 < s.count && back < HISTORY - 2; back++) {
            double[] a = at(s, back);
            double[] b = at(s, back + 2);
            best = Math.max(best, Math.hypot(a[0] - b[0], a[1] - b[1]) / 2);
        }
        return best;
    }

    /** The way the player has been going lately, as a yaw; their view when they hardly moved. */
    private static double heading(State s, Player player) {
        int back = Math.min(3, s.count - 1);
        if (back >= 1) {
            double[] now = at(s, 0);
            double[] then = at(s, back);
            double dx = now[0] - then[0];
            double dz = now[1] - then[1];
            if (Math.hypot(dx, dz) >= 0.05 * back) {
                return Math.toDegrees(Math.atan2(-dx, dz));
            }
        }
        return player.getLocation().getYaw();
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        long now = Bukkit.getCurrentTick();
        long nanos = System.nanoTime();
        for (Player player : Bukkit.getOnlinePlayers()) {
            State s = state(player);
            sample(player, s);
            if (player.isSprinting() && !isSliding(player)) {
                s.sprintSeen = nanos;
                if (s.sprintTicks < Integer.MAX_VALUE / 2) {
                    s.sprintTicks++;
                }
            }
        }
        for (Slide slide : new ArrayList<>(slides.values())) {
            advance(slide, now);
        }
        // After the slides under way: a new one sits down this tick and sets off on the next.
        for (Player player : Bukkit.getOnlinePlayers()) {
            State s = states.get(player.getUniqueId());
            if (s != null && s.pending) {
                s.pending = false;
                begin(player, s);
            }
        }
    }

    /** Seats the player and sets off, if nothing has changed since the second tap. */
    private void begin(Player player, State s) {
        String why = blocker(player, s, player.getCurrentInput());
        if (why != null) {
            refuse(player, s, why, true);
            return;
        }
        Location feet = player.getLocation();
        World world = feet.getWorld();
        double boost = setting("boost", 1.4);
        double speed = Math.min(setting("max-speed", 0.45), Math.max(recentSpeed(s), setting("min-start-speed", 0.286)) * boost);
        double yaw = heading(s, player);
        ItemDisplay seat = world.spawn(new Location(world, feet.getX(), feet.getY(), feet.getZ(), (float) yaw, 0), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setTeleportDuration(2);
            d.getPersistentDataContainer().set(Keys.ENTITY_ROLE, PersistentDataType.STRING, SEAT);
        });
        if (!seat.addPassenger(player)) {
            seat.remove();
            refuse(player, s, "上不了滑铲座位", false);
            return;
        }
        Slide slide = new Slide(player, seat, feet.getX(), feet.getY(), feet.getZ(), yaw, speed, plugin.stamina().stamina(player));
        slides.put(player.getUniqueId(), slide);
        // Before the riders are moved this tick: a sneak key still down would take the player off again.
        player.setSneaking(false);
        player.setPose(Pose.STANDING, true);
        // A rider does not sprint (the vanilla client stops on a seat too): the slide drains its own stamina.
        player.setSprinting(false);
        plugin.stamina().spend(player, Math.max(0, setting("start-cost", 0)));
        plugin.footsteps().slide(player);
        dust(slide, 10);
        if (config().getBoolean("debug", false)) {
            plugin.getLogger().info(String.format("[debug] slide %s start at %.2f,%.2f,%.2f yaw=%.1f speed=%.3f stamina=%.1f",
                    player.getName(), slide.x, slide.y, slide.z, yaw, speed, slide.staminaBefore));
        }
    }

    private void advance(Slide slide, long now) {
        Player player = slide.player;
        ItemDisplay seat = slide.seat;
        if (!player.isOnline() || !player.isValid() || !seat.isValid() || player.getVehicle() != seat
                || player.getWorld() != seat.getWorld() || plugin.downed().isDowned(player) || !allowed(player)) {
            end(slide, "interrupted", false);
            return;
        }
        measure(slide);
        // Every tick before the riders move (this runs before the world ticks): see the class comment.
        if (player.isSneaking()) {
            player.setSneaking(false);
        }
        if (player.getPose() != Pose.STANDING || !player.hasFixedPose()) {
            player.setPose(Pose.STANDING, true);
        }
        Input input = player.getCurrentInput();
        if (input.isJump()) {
            end(slide, "jump", true);
            return;
        }
        double steer = setting("steer-degrees", 1.5);
        if (input.isLeft() != input.isRight()) {
            slide.yaw += input.isLeft() ? -steer : steer;
        }
        double friction = move(slide);
        if (friction < 0) {
            end(slide, friction == WALL ? "wall" : "ledge", friction != WALL);
            return;
        }
        plugin.stamina().spend(player, plugin.stamina().sprintDrain() * Math.max(0, setting("stamina-multiplier", 2.0)));
        slide.ticks++;
        if (slide.ticks % 3 == 0) {
            dust(slide, 4);
        }
        slide.speed *= friction;
        if (slide.speed < setting("stop-speed", 0.12)) {
            end(slide, "stopped", true);
        } else if (slide.ticks >= setting("max-ticks", 20)) {
            end(slide, "max-ticks", true);
        }
    }

    /**
     * Notes the body's box against the ground it sits over: the box as the rider was put on the seat
     * (as they sat down, then by every world tick), against the seat, which has not moved since.
     */
    private void measure(Slide slide) {
        Player player = slide.player;
        BoundingBox box = player.getBoundingBox();
        double ground = slide.seat.getY();
        slide.bottomLow = Math.min(slide.bottomLow, box.getMinY() - ground);
        slide.bottomHigh = Math.max(slide.bottomHigh, box.getMinY() - ground);
        slide.topLow = Math.min(slide.topLow, box.getMaxY() - ground);
        slide.topHigh = Math.max(slide.topHigh, box.getMaxY() - ground);
        if (config().getBoolean("debug", false)) {
            plugin.getLogger().info(String.format("[debug] slide %s tick=%d ground=%.3f box=%.3f..%.3f speed=%.3f at=%.2f,%.2f",
                    player.getName(), slide.ticks, ground, box.getMinY(), box.getMaxY(), slide.speed, player.getX(), player.getZ()));
        }
    }

    private static final double WALL = -1;
    private static final double LEDGE = -2;

    /**
     * Moves the seat one tick along the slide, over steps and down drops; returns the friction for
     * the ground it went over, or {@link #WALL} (nothing moved) or {@link #LEDGE} (no ground in reach).
     * A step the full way that runs into something is tried at half the way (the edge of a stair).
     * The seat is moved with its rider on it (RETAIN_PASSENGERS: deprecated for removal since 1.21.10,
     * but nothing else in the 1.21.11 API moves an entity that carries a passenger).
     */
    @SuppressWarnings("removal")
    private double move(Slide slide) {
        World world = slide.seat.getWorld();
        double dx = slide.dirX();
        double dz = slide.dirZ();
        double stepUp = Math.max(0, setting("step-up", 0.5));
        double maxDrop = Math.max(0, setting("max-drop", 1.0));
        for (double share : new double[]{1.0, 0.5}) {
            double nx = slide.x + dx * slide.speed * share;
            double nz = slide.z + dz * slide.speed * share;
            Double floor = floor(world, nx, nz, slide.y + stepUp, slide.y - maxDrop);
            if (floor == null) {
                return share == 1.0 ? LEDGE : WALL;
            }
            if (world.hasCollisionsIn(new BoundingBox(nx - HALF, floor + 0.01, nz - HALF, nx + HALF, floor + BODY, nz + HALF))) {
                continue;
            }
            double rise = floor - slide.y;
            slide.x = nx;
            slide.y = floor;
            slide.z = nz;
            slide.seat.teleport(new Location(world, nx, floor, nz, (float) slide.yaw, 0), TeleportFlag.EntityState.RETAIN_PASSENGERS);
            return rise > 0.01 ? setting("friction-uphill", 0.85) : rise < -0.01 ? setting("friction-downhill", 0.965) : setting("friction", 0.93);
        }
        return WALL;
    }

    /**
     * The top of the highest collision box under a body 0.6 wide at {@code (x, z)} whose top lies
     * between {@code low} and {@code high}: the ground it can slide onto. Null when there is none.
     */
    private static @Nullable Double floor(World world, double x, double z, double high, double low) {
        Double best = null;
        int x0 = (int) Math.floor(x - HALF);
        int x1 = (int) Math.floor(x + HALF - 1e-7);
        int z0 = (int) Math.floor(z - HALF);
        int z1 = (int) Math.floor(z + HALF - 1e-7);
        int y0 = (int) Math.floor(low) - 1;
        int y1 = (int) Math.floor(high);
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                for (int by = y1; by >= y0; by--) {
                    Block block = world.getBlockAt(bx, by, bz);
                    if (block.getType().isAir()) {
                        continue;
                    }
                    for (BoundingBox part : block.getCollisionShape().getBoundingBoxes()) {
                        double top = by + part.getMaxY();
                        if (top > high + 1e-6 || top < low - 1e-6 || best != null && top <= best) {
                            continue;
                        }
                        // Only boxes under the body, not the ones beside it in the same block.
                        if (bx + part.getMaxX() <= x - HALF || bx + part.getMinX() >= x + HALF
                                || bz + part.getMaxZ() <= z - HALF || bz + part.getMinZ() >= z + HALF) {
                            continue;
                        }
                        best = top;
                    }
                }
            }
        }
        return best;
    }

    /** Dust kicked up from the ground under the seat. */
    private void dust(Slide slide, int count) {
        World world = slide.seat.getWorld();
        Block under = world.getBlockAt((int) Math.floor(slide.x), (int) Math.floor(slide.y - 0.01), (int) Math.floor(slide.z));
        if (under.getType().isAir()) {
            return;
        }
        BlockData data = under.getBlockData();
        Location at = new Location(world, slide.x + slide.dirX() * 0.5, slide.y + 0.05, slide.z + slide.dirZ() * 0.5);
        world.spawnParticle(Particle.BLOCK, at, count, 0.25, 0.03, 0.25, 0, data);
    }

    // ------------------------------------------------------------------ the end

    /** Ends the player's slide (when knocked down, killed or leaving), putting them on their feet. */
    public void stop(Player player) {
        Slide slide = slides.get(player.getUniqueId());
        if (slide != null) {
            end(slide, "stopped by the game", false);
        }
    }

    /**
     * Takes the rider off the seat, which puts them on it at ground level (vanilla's dismount, a
     * teleport that keeps their view and their client's momentum), and gives them the slide's speed
     * if {@code carry}.
     */
    private void end(Slide slide, String reason, boolean carry) {
        Player player = slide.player;
        slides.remove(player.getUniqueId());
        ItemDisplay seat = slide.seat;
        double seatX = seat.isValid() ? seat.getX() : slide.x;
        double seatY = seat.isValid() ? seat.getY() : slide.y;
        double seatZ = seat.isValid() ? seat.getZ() : slide.z;
        if (player.getVehicle() == seat) {
            releasing = true;
            try {
                seat.removePassenger(player);
            } finally {
                releasing = false;
            }
        }
        seat.remove();
        if (player.isOnline() && player.getWorld() == seat.getWorld() && player.getY() < seatY - 0.3
                && player.getLocation().distanceSquared(new Location(seat.getWorld(), seatX, player.getY(), seatZ)) < 4) {
            // The dismount did not put them up (refused by someone): onto the ground under the seat, view kept.
            player.teleport(new Location(seat.getWorld(), seatX, seatY, seatZ, player.getYaw(), player.getPitch()));
        }
        settle(player);
        if (carry && player.isOnline() && !player.isInsideVehicle()) {
            player.setVelocity(new Vector(slide.dirX() * slide.speed, player.getVelocity().getY(), slide.dirZ() * slide.speed));
        }
        record(player, slide, reason, seatX, seatY, seatZ);
    }

    /** The pose and the sneak key back to what the player's client has: crouching if they still hold it. */
    private void settle(Player player) {
        if (!player.isOnline()) {
            return;
        }
        if (player.hasFixedPose() && player.getPose() == Pose.STANDING && !plugin.downed().isDowned(player)) {
            player.setPose(Pose.STANDING, false);
        }
        boolean sneak = player.getCurrentInput().isSneak() && !plugin.downed().isDowned(player);
        if (player.isSneaking() != sneak) {
            player.setSneaking(sneak);
        }
    }

    private void record(Player player, Slide slide, String reason, double x, double y, double z) {
        State s = state(player);
        s.endedTick = Bukkit.getCurrentTick();
        s.sprintTicks = 0;
        s.firstDown = 0;
        s.firstUp = 0;
        double spent = slide.staminaBefore - plugin.stamina().stamina(player);
        double distance = Math.hypot(x - slide.startX, z - slide.startZ);
        s.last = new Summary(s.endedTick, reason, slide.ticks, distance, spent, slide.startX, slide.startY, slide.startZ, x, y, z,
                slide.bottomLow == Double.MAX_VALUE ? Double.NaN : slide.bottomLow,
                slide.bottomHigh == -Double.MAX_VALUE ? Double.NaN : slide.bottomHigh,
                slide.topLow == Double.MAX_VALUE ? Double.NaN : slide.topLow,
                slide.topHigh == -Double.MAX_VALUE ? Double.NaN : slide.topHigh, slide.startSpeed);
        if (config().getBoolean("debug", false)) {
            plugin.getLogger().info(String.format("[debug] slide %s end reason=%s ticks=%d distance=%.2f spent=%.2f at=%.2f,%.2f,%.2f box=%.3f..%.3f/%.3f..%.3f",
                    player.getName(), reason, slide.ticks, distance, spent, x, y, z,
                    s.last.minYLow(), s.last.minYHigh(), s.last.maxYLow(), s.last.maxYHigh()));
        }
    }

    /**
     * A dismount this service did not ask for (a teleport, an extraction, death) goes ahead, and
     * ends the slide: the rider is put down on the seat by vanilla, and the seat goes once that is done.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDismount(EntityDismountEvent event) {
        if (releasing || event.isCancelled() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        Slide slide = slides.get(player.getUniqueId());
        if (slide == null || event.getDismounted() != slide.seat) {
            return;
        }
        slides.remove(player.getUniqueId());
        record(player, slide, "dismounted", slide.x, slide.y, slide.z);
        Bukkit.getScheduler().runTask(plugin, () -> {
            slide.seat.remove();
            if (!isSliding(player)) {
                settle(player);
            }
        });
    }

    /** First of all, so whatever saves where the player was finds them on their feet. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer());
        states.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        stop(event.getPlayer());
    }

    /** Puts everyone sliding on their feet and removes the seats (the plugin is shutting down). */
    public void shutdown() {
        for (Slide slide : new ArrayList<>(slides.values())) {
            end(slide, "shutdown", false);
        }
        // Seats are never saved, but one left by a crash in a loaded world would sit there unused.
        for (World world : Bukkit.getWorlds()) {
            List<ItemDisplay> stray = new ArrayList<>();
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (SEAT.equals(display.getPersistentDataContainer().get(Keys.ENTITY_ROLE, PersistentDataType.STRING))) {
                    stray.add(display);
                }
            }
            stray.forEach(Entity::remove);
        }
    }
}
