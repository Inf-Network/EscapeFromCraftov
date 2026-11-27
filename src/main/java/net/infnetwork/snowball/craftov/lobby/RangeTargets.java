package net.infnetwork.snowball.craftov.lobby;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.entity.TeleportFlag;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.BodyPart;
import net.infnetwork.snowball.craftov.combat.HealthService;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The private range's targets (#165): steel silhouettes that fall when shot down and stand up again,
 * the same silhouettes moving on rails at three speeds, and paper bullseyes that score rings and
 * keep the hit rate.
 *
 * <p>A silhouette is an invisible zombie without AI (the hitbox, a player's size, so rounds, knives,
 * grenades, hit zones and lag compensation treat it as they treat a soldier) carrying the picture as
 * passengers: a steel plate (an item display), the base it is hinged to (a block display) and its
 * label. The plate turns on its hinge through the display's own interpolation, so the vanilla client
 * draws the fall and the rise smoothly, and riding the hitbox it moves exactly where the hitbox is
 * drawn. It takes damage like a player without armour ({@code range.targets.hp}); at none left it
 * falls back flat, rounds and knives pass over it, and after {@code range.targets.down-seconds} it
 * stands up again with its health back. A moving one rides its rail all the while. Its hitbox is
 * recorded for lag compensation (HitboxHistory), so a moving target is hit where the shooter sees it.
 *
 * <p>A bullseye is an invisible slime (a 1.04 cube) behind a paper face of ten rings, 5 cm each.
 * Every round fired on the range whose line passes within {@link #AIMED} of a board's centre counts as
 * a shot at that board; a hit scores the ring where the line crosses the face, leaves a hole there
 * (the latest one outlined) and updates the board's tally: hits, hit rate, average and last ring.
 * Leaving the range, or the rack's reset button, clears it.
 */
public final class RangeTargets {

    /** How high above a zombie's feet its passengers ride (vanilla's passenger attachment for a zombie). */
    static final float SEAT = 2.0125f;
    /** The hinge the plate turns on: the top of its base. */
    private static final float HINGE = 0.12f;
    /** The plate model is drawn at half size (an item model has to fit in 3 blocks) and shown doubled. */
    private static final float PLATE_SCALE = 2f;
    /** A board's slime size: a 1.04 cube, the face as wide as the paper. */
    private static final int BOARD_SIZE = 2;
    /** Ring width: ten rings across a 0.5 radius. */
    private static final double RING = 0.05;
    /** A round whose line passes this close to a board's centre was fired at it, hit or miss. */
    private static final double AIMED = 1.5;
    private static final int MAX_HOLES = 30;
    /** Ticks a rail target waits at each end before it turns back. */
    private static final int PAUSE = 10;
    private static final String[] SPEED_NAMES = {"慢速", "中速", "快速"};
    private static final double[] DEFAULT_SPEEDS = {2.0, 3.5, 5.5};

    private enum State { STANDING, DOWN, RISING }

    private record Frame(long at, float angle, int ticks, @Nullable String sound, float pitch) {
    }

    /** A silhouette, standing or on a rail. */
    private static final class Silhouette {
        final int plot;
        final Zombie body;
        final ItemDisplay plate;
        final TextDisplay label;
        final String name;
        /** Rail ends (x) for a moving target, NaN for a standing one; blocks per tick; where in its run it starts. */
        final double x0;
        final double x1;
        final double step;
        final int phase;
        int runs;
        State state = State.STANDING;
        double hp;
        long downAt = -1;
        long riseAt;
        long standAt;
        int hits;
        long firstHit = -1;
        @Nullable String last;
        long flashUntil;
        final ArrayDeque<Frame> frames = new ArrayDeque<>();

        Silhouette(int plot, Zombie body, ItemDisplay plate, TextDisplay label, String name, double x0, double x1, double step, int phase) {
            this.plot = plot;
            this.body = body;
            this.plate = plate;
            this.label = label;
            this.name = name;
            this.x0 = x0;
            this.x1 = x1;
            this.step = step;
            this.phase = phase;
        }

        boolean moving() {
            return !Double.isNaN(x0);
        }
    }

    /** A bullseye and its tally. */
    private static final class Board {
        final int plot;
        final Slime body;
        final TextDisplay tally;
        final String name;
        final double cx;
        final double cy;
        final double faceZ;
        int shots;
        int hits;
        int rings;
        /** The ring of the last shot at it, 0 on the paper outside the rings, -1 a miss, -2 none yet. */
        int last = -2;
        final ArrayDeque<ItemDisplay> holes = new ArrayDeque<>();

        Board(int plot, Slime body, TextDisplay tally, String name, double cx, double cy, double faceZ) {
            this.plot = plot;
            this.body = body;
            this.tally = tally;
            this.name = name;
            this.cx = cx;
            this.cy = cy;
            this.faceZ = faceZ;
        }
    }

    private static final class Plot {
        final List<Silhouette> silhouettes = new ArrayList<>();
        final List<Board> boards = new ArrayList<>();
    }

    private final CraftovPlugin plugin;
    private final Map<Integer, Plot> plots = new HashMap<>();
    private final Map<UUID, Silhouette> silhouettes = new HashMap<>();
    private final Map<UUID, Board> boards = new HashMap<>();

    RangeTargets(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        // Every tick: the rails move a step a tick, and the fall's keyframes are a few ticks apart.
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private double maxHp() {
        return Math.max(1, plugin.getConfig().getDouble("range.targets.hp", HealthService.MAX_HP));
    }

    private long downTicks() {
        return Math.max(1, Math.round(plugin.getConfig().getDouble("range.targets.down-seconds", 3) * 20));
    }

    // ------------------------------------------------------------------ building

    /** Puts up the targets of plot {@code plot}, {@code dx} blocks east of the hangar; what it spawns goes into {@code spawned}. */
    void spawn(World world, int plot, int dx, List<Entity> spawned) {
        Plot p = new Plot();
        plots.put(plot, p);
        double floor = RangeBuilder.Y + 1;
        for (int[] at : RangeBuilder.SILHOUETTES) {
            int meters = RangeBuilder.RAIL_Z - at[1];
            Silhouette s = silhouette(world, plot, new Location(world, dx + at[0] + 0.5, floor, at[1] + 0.5, 0, 0),
                    "人物靶 " + meters + " m", Double.NaN, Double.NaN, 0, 0, meters, spawned);
            p.silhouettes.add(s);
        }
        List<Double> speeds = plugin.getConfig().getDoubleList("range.moving-speeds");
        for (int i = 0; i < RangeBuilder.RAILS_Z.length; i++) {
            int z = RangeBuilder.RAILS_Z[i];
            double speed = i < speeds.size() ? speeds.get(i) : DEFAULT_SPEEDS[Math.min(i, DEFAULT_SPEEDS.length - 1)];
            double x0 = dx + RangeBuilder.RAIL_X0 + 0.5;
            double x1 = dx + RangeBuilder.RAIL_X1 + 0.5;
            String name = "移动靶 " + (RangeBuilder.RAIL_Z - z) + " m · " + SPEED_NAMES[Math.min(i, SPEED_NAMES.length - 1)]
                    + " " + trim(speed) + " m/s";
            // Started at different points of their runs, so the three do not cross the lane in step.
            Silhouette s = silhouette(world, plot, new Location(world, x0, floor, z + 0.5, 0, 0), name, x0, x1,
                    Math.max(0.01, speed) / 20.0, i * 17, RangeBuilder.RAIL_Z - z, spawned);
            p.silhouettes.add(s);
        }
        for (int[] at : RangeBuilder.BULLSEYES) {
            p.boards.add(board(world, plot, dx + at[0] + 0.5, floor, at[1] + 0.5, RangeBuilder.RAIL_Z - at[1], spawned));
        }
        String[] signs = {
                "<b><text>人物靶</b>\n<muted>4 / 7 / 11 m · 打倒后 " + trim(downTicks() / 20.0) + " 秒升起",
                "<b><text>标靶</b>\n<muted>10 / 25 m · 环数与命中率",
                "<b><text>移动靶</b>\n<muted>10 m 慢速 · 20 m 中速 · 30 m 快速"};
        for (int i = 0; i < signs.length; i++) {
            String text = signs[i];
            // Over the lane, above the line of sight down it.
            spawned.add(world.spawn(new Location(world, dx + RangeBuilder.LANE_SIGN_X[i], floor + 3.0, RangeBuilder.RAIL_Z - 1.5),
                    TextDisplay.class, d -> {
                        d.text(Text.mm(text));
                        d.setBillboard(Display.Billboard.VERTICAL);
                        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.8f, 0.8f, 0.8f),
                                new Quaternionf()));
                        d.setPersistent(false);
                        d.setBackgroundColor(Color.fromARGB(120, 10, 12, 16));
                    }));
        }
    }

    private Silhouette silhouette(World world, int plot, Location feet, String name, double x0, double x1, double step, int phase,
                                  int meters, List<Entity> spawned) {
        // Readable from the firing line: larger the farther the target stands.
        float scale = (float) Math.max(0.7, Math.min(1.8, 0.07 * meters));
        Zombie body = world.spawn(feet, Zombie.class, false, z -> {
            z.setAI(false);
            z.setAdult();
            z.setSilent(true);
            z.setInvisible(true);
            z.setGravity(false);
            z.setCollidable(false);
            z.setCanPickupItems(false);
            z.setPersistent(false);
            // Never despawned for being far from players (see the training course's targets);
            // RangeService#release removes it with the plot.
            z.setRemoveWhenFarAway(false);
            z.setShouldBurnInDay(false);
            z.getPersistentDataContainer().set(Keys.key("target"), PersistentDataType.BYTE, (byte) 1);
        });
        ItemStack picture = ItemStack.of(Material.PAPER);
        picture.setData(DataComponentTypes.ITEM_MODEL, Key.key("craftov", "fx/target_silhouette"));
        ItemDisplay plate = world.spawn(facingShooter(feet), ItemDisplay.class, d -> {
            d.setItemStack(picture);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(plate(0));
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(false);
        });
        BlockDisplay base = world.spawn(feet, BlockDisplay.class, d -> {
            d.setBlock(Material.POLISHED_BLACKSTONE.createBlockData());
            d.setTransformation(new Transformation(new Vector3f(-0.34f, -SEAT, -0.16f), new Quaternionf(),
                    new Vector3f(0.68f, HINGE, 0.32f), new Quaternionf()));
            d.setPersistent(false);
        });
        TextDisplay label = world.spawn(feet, TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setTransformation(new Transformation(new Vector3f(0, 2.15f - SEAT, 0), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
            d.setPersistent(false);
            d.setBackgroundColor(Color.fromARGB(120, 10, 12, 16));
        });
        body.addPassenger(base);
        body.addPassenger(plate);
        body.addPassenger(label);
        spawned.add(body);
        spawned.add(plate);
        spawned.add(base);
        spawned.add(label);
        Silhouette s = new Silhouette(plot, body, plate, label, name, x0, x1, step, phase);
        s.hp = maxHp();
        silhouettes.put(body.getUniqueId(), s);
        relabel(s);
        return s;
    }

    /**
     * Where an item display stands to show a model's south face (+Z) to the firing line, south of it:
     * the client draws item displays turned half round, so it is turned back with a yaw of 180.
     */
    private static Location facingShooter(Location at) {
        Location out = at.clone();
        out.setYaw(180);
        out.setPitch(0);
        return out;
    }

    /**
     * The plate turned back by {@code angle} degrees (0 upright, -90 flat on its back) on the hinge:
     * the model's origin is the hinge, so turning about it and then dropping it from the seat to the
     * hinge keeps the bottom edge in place all the way down.
     */
    private static Transformation plate(float angle) {
        return new Transformation(new Vector3f(0, HINGE - SEAT, 0), new Quaternionf().rotateX((float) Math.toRadians(angle)),
                new Vector3f(PLATE_SCALE, PLATE_SCALE, PLATE_SCALE), new Quaternionf());
    }

    private Board board(World world, int plot, double x, double floor, double z, int meters, List<Entity> spawned) {
        // The board's centre at about chest height; it rests on the fence post under it.
        double feet = floor + 0.93;
        Slime body = world.spawn(new Location(world, x, feet, z, 0, 0), Slime.class, false, s -> {
            s.setSize(BOARD_SIZE);
            s.setAI(false);
            s.setSilent(true);
            s.setInvisible(true);
            s.setGravity(false);
            s.setCollidable(false);
            s.setPersistent(false);
            s.setRemoveWhenFarAway(false);
            s.getPersistentDataContainer().set(Keys.key("target"), PersistentDataType.BYTE, (byte) 1);
        });
        double half = body.getBoundingBox().getWidthX() / 2;
        double cy = feet + body.getBoundingBox().getHeight() / 2;
        double faceZ = body.getBoundingBox().getMaxZ();
        float size = (float) (half * 2);
        ItemStack paper = ItemStack.of(Material.PAPER);
        paper.setData(DataComponentTypes.ITEM_MODEL, Key.key("craftov", "fx/target_bullseye"));
        spawned.add(world.spawn(facingShooter(new Location(world, x, cy, faceZ + 0.004)), ItemDisplay.class, d -> {
            d.setItemStack(paper);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(size, size, size), new Quaternionf()));
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(false);
        }));
        // Big enough to read from the firing line: twice the size for the far board.
        float scale = meters > 15 ? 2.0f : 1.2f;
        TextDisplay tally = world.spawn(new Location(world, x, cy + half + 0.25, z), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
            d.setPersistent(false);
            d.setBackgroundColor(Color.fromARGB(140, 10, 12, 16));
            d.addScoreboardTag("craftov_range_tally");
        });
        spawned.add(body);
        spawned.add(tally);
        Board b = new Board(plot, body, tally, meters + " m 标靶", x, cy, faceZ);
        boards.put(body.getUniqueId(), b);
        retally(b);
        return b;
    }

    /** Forgets plot {@code plot}'s targets; RangeService removes their entities with the plot. */
    void clear(int plot) {
        Plot p = plots.remove(plot);
        if (p == null) {
            return;
        }
        for (Silhouette s : p.silhouettes) {
            silhouettes.remove(s.body.getUniqueId());
        }
        for (Board b : p.boards) {
            boards.remove(b.body.getUniqueId());
            b.holes.forEach(Entity::remove);
            b.holes.clear();
        }
    }

    // ------------------------------------------------------------------ queries

    /** A silhouette that is down or still getting up: rounds, knives and blasts pass it by. */
    public boolean isDown(Entity entity) {
        Silhouette s = silhouettes.get(entity.getUniqueId());
        return s != null && s.state != State.STANDING;
    }

    /** True when the hit {@code victim} just took knocked it down (the knock-down marker and sound). */
    public boolean knockedDownNow(LivingEntity victim) {
        Silhouette s = silhouettes.get(victim.getUniqueId());
        return s != null && s.state == State.DOWN && s.downAt == Bukkit.getCurrentTick();
    }

    /** A bullseye's hitbox, which has no head or limbs. */
    public boolean isBoard(Entity entity) {
        return boards.containsKey(entity.getUniqueId());
    }

    /** The sound of a round striking a range target (steel or the paper on its board), or null for anything else. */
    public @Nullable String impactSound(Entity entity) {
        return silhouettes.containsKey(entity.getUniqueId()) ? "craftov:impact.metal"
                : boards.containsKey(entity.getUniqueId()) ? "craftov:impact.wood" : null;
    }

    /** Sparks off steel, paper bits off a board, instead of blood. False for anything else. */
    public boolean impact(LivingEntity entity, Location at) {
        World world = at.getWorld();
        if (silhouettes.containsKey(entity.getUniqueId())) {
            world.spawnParticle(Particle.ELECTRIC_SPARK, at, 6, 0.05, 0.05, 0.05, 0.25);
            return true;
        }
        if (boards.containsKey(entity.getUniqueId())) {
            world.spawnParticle(Particle.BLOCK, at, 5, 0.04, 0.04, 0.02, Material.WHITE_WOOL.createBlockData());
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ hits

    /**
     * Damage dealt to a range target by a round, a knife or a blast (DamageService#deal, before
     * LobbyService cancels it): a silhouette loses as much health and falls at none.
     */
    void damaged(Entity victim, double amount) {
        Silhouette s = silhouettes.get(victim.getUniqueId());
        if (s == null || s.state != State.STANDING || amount <= 0) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        if (s.firstHit < 0) {
            s.firstHit = now;
        }
        s.hits++;
        s.hp -= amount;
        // A short outline flash on the plate, seen from the firing line however far it is.
        s.plate.setGlowColorOverride(Color.fromRGB(255, 214, 120));
        s.plate.setGlowing(true);
        s.flashUntil = now + 3;
        if (s.hp <= 0) {
            knockDown(s, now);
        }
        relabel(s);
    }

    /**
     * A round fired by {@code shooter} on the range along {@code dir} from {@code eye}, which hit
     * {@code hit} ({@code part}, {@code dealt} damage) or nothing. Called for every round (each pellet).
     */
    public void shot(Player shooter, Vector eye, Vector dir, @Nullable LivingEntity hit, BodyPart part, double dealt) {
        Integer plot = plugin.range().plotOf(shooter);
        Plot p = plot == null ? null : plots.get(plot);
        if (p == null) {
            return;
        }
        Silhouette s = hit == null ? null : silhouettes.get(hit.getUniqueId());
        if (s != null && s.plot == plot) {
            s.last = part.displayName() + " <white>-" + Math.round(dealt);
            relabel(s);
            Notify.status(shooter, "range_target", "<muted>" + s.name + " · " + s.last
                    + (s.state == State.STANDING ? " <muted>· 剩余 <white>" + (int) Math.ceil(s.hp) : " <ok>· 击倒"));
        }
        Board aimed = null;
        double best = AIMED;
        double ax = 0;
        double ay = 0;
        for (Board b : p.boards) {
            // Only rounds going down the range, towards the board's face.
            if (dir.getZ() > -1e-6) {
                continue;
            }
            double t = (b.faceZ - eye.getZ()) / dir.getZ();
            if (t <= 0) {
                continue;
            }
            double x = eye.getX() + dir.getX() * t;
            double y = eye.getY() + dir.getY() * t;
            double off = Math.hypot(x - b.cx, y - b.cy);
            if (off < best) {
                best = off;
                aimed = b;
                ax = x;
                ay = y;
            }
        }
        if (aimed == null) {
            return;
        }
        aimed.shots++;
        if (hit != null && hit.getUniqueId().equals(aimed.body.getUniqueId())) {
            int ring = ring(best);
            aimed.hits++;
            aimed.rings += ring;
            aimed.last = ring;
            hole(aimed, ax, ay);
        } else {
            aimed.last = -1;
        }
        retally(aimed);
        Notify.status(shooter, "range_target", "<muted>" + aimed.name + " · " + lastShot(aimed) + " <muted>· 命中 <white>"
                + aimed.hits + "/" + aimed.shots + " <muted>(" + percent(aimed) + ")");
    }

    /** The ring a hit {@code off} blocks from the centre scores: 10 in the middle, 1 at the edge, 0 outside. */
    static int ring(double off) {
        if (off >= RING * 10) {
            return 0;
        }
        return Math.max(1, Math.min(10, 10 - (int) Math.floor(off / RING)));
    }

    private void hole(Board b, double x, double y) {
        World world = b.body.getWorld();
        ItemStack mark = ItemStack.of(Material.PAPER);
        mark.setData(DataComponentTypes.ITEM_MODEL, Key.key("craftov", "fx/hole"));
        ItemDisplay previous = b.holes.peekLast();
        if (previous != null) {
            previous.setGlowing(false);
        }
        ItemDisplay hole = world.spawn(facingShooter(new Location(world, x, y, b.faceZ + 0.008)), ItemDisplay.class, d -> {
            d.setItemStack(mark);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.075f, 0.075f, 0.075f),
                    new Quaternionf()));
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setPersistent(false);
            d.setGlowColorOverride(Color.fromRGB(255, 77, 77));
            d.setGlowing(true);
        });
        b.holes.addLast(hole);
        while (b.holes.size() > MAX_HOLES) {
            b.holes.pollFirst().remove();
        }
    }

    /** Clears the tallies and holes of {@code player}'s boards. */
    public void resetTally(Player player) {
        Integer plot = plugin.range().plotOf(player);
        Plot p = plot == null ? null : plots.get(plot);
        if (p == null) {
            return;
        }
        for (Board b : p.boards) {
            b.shots = 0;
            b.hits = 0;
            b.rings = 0;
            b.last = -2;
            b.holes.forEach(Entity::remove);
            b.holes.clear();
            retally(b);
        }
    }

    // ------------------------------------------------------------------ falling and rising

    private void knockDown(Silhouette s, long now) {
        s.state = State.DOWN;
        s.downAt = now;
        s.riseAt = now + downTicks();
        s.frames.clear();
        // Tipping slowly, then quicker, a small bounce on landing: about a third of a second.
        s.frames.add(new Frame(now, -24, 3, "minecraft:block.iron_trapdoor.close", 0.7f));
        s.frames.add(new Frame(now + 3, -90, 3, null, 1));
        s.frames.add(new Frame(now + 6, -80, 2, "minecraft:block.anvil.land", 1.9f));
        s.frames.add(new Frame(now + 8, -90, 2, null, 1));
    }

    private void rise(Silhouette s, long now) {
        s.state = State.RISING;
        s.frames.clear();
        // Pushed back up by its motor, a little past upright, then settling.
        s.frames.add(new Frame(now, 6, 10, "minecraft:block.piston.extend", 1.3f));
        s.frames.add(new Frame(now + 10, 0, 3, null, 1));
        // Hittable again once it is most of the way up.
        s.standAt = now + 8;
    }

    private void stand(Silhouette s) {
        s.state = State.STANDING;
        s.hp = maxHp();
        s.hits = 0;
        s.firstHit = -1;
        s.last = null;
        relabel(s);
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        for (Plot p : plots.values()) {
            for (Silhouette s : p.silhouettes) {
                if (!s.body.isValid()) {
                    continue;
                }
                if (s.moving()) {
                    move(s);
                }
                if (s.state == State.DOWN && now >= s.riseAt) {
                    rise(s, now);
                }
                if (s.state == State.RISING && now >= s.standAt) {
                    stand(s);
                }
                while (!s.frames.isEmpty() && s.frames.peekFirst().at() <= now) {
                    Frame f = s.frames.pollFirst();
                    s.plate.setInterpolationDelay(0);
                    s.plate.setInterpolationDuration(f.ticks());
                    s.plate.setTransformation(plate(f.angle()));
                    if (f.sound() != null) {
                        s.body.getWorld().playSound(s.body.getLocation(), f.sound(), SoundCategory.BLOCKS, 0.6f, f.pitch());
                    }
                }
                if (s.flashUntil > 0 && now >= s.flashUntil) {
                    s.flashUntil = 0;
                    s.plate.setGlowing(false);
                }
            }
        }
    }

    /**
     * One step along the rail: out, a pause, back, a pause. The hitbox is moved and its passengers
     * with it (RETAIN_PASSENGERS, as SlideService moves its seat); the client eases a zombie between
     * position updates as it does a soldier.
     */
    @SuppressWarnings("removal")
    private void move(Silhouette s) {
        double length = s.x1 - s.x0;
        int travel = (int) Math.ceil(length / s.step);
        int cycle = 2 * (travel + PAUSE);
        int k = Math.floorMod(s.runs++ + s.phase, cycle);
        double along;
        if (k < PAUSE) {
            along = 0;
        } else if (k < PAUSE + travel) {
            along = (k - PAUSE) * s.step;
        } else if (k < 2 * PAUSE + travel) {
            along = length;
        } else {
            along = length - (k - 2 * PAUSE - travel) * s.step;
        }
        Location at = s.body.getLocation();
        at.setX(s.x0 + Math.max(0, Math.min(length, along)));
        at.setYaw(0);
        at.setPitch(0);
        s.body.teleport(at, TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    // ------------------------------------------------------------------ labels

    private void relabel(Silhouette s) {
        StringBuilder sb = new StringBuilder("<b><text>").append(s.name).append("</b>\n");
        if (s.state == State.STANDING) {
            double share = s.hp / maxHp();
            String color = share > 0.6 ? "ok" : share > 0.3 ? "accent" : "danger";
            sb.append(Text.bar(share, 20, color, "dim")).append(" <white>").append((int) Math.ceil(Math.max(0, s.hp)));
            if (s.last != null) {
                sb.append("\n<muted>").append(s.last);
            }
        } else if (s.hits <= 1) {
            sb.append("<ok>一发击倒");
        } else {
            // From the first hit to the one that knocked it down: the time to kill.
            double seconds = (s.downAt - s.firstHit) / 20.0;
            sb.append("<ok>击倒 <white>").append(s.hits).append(" <muted>发 · <white>").append(String.format("%.2f", seconds))
                    .append(" <muted>秒");
            if (s.last != null) {
                sb.append("\n<muted>").append(s.last);
            }
        }
        s.label.text(Text.mm(sb.toString()));
    }

    private void retally(Board b) {
        String avg = b.hits == 0 ? "-" : String.format("%.1f", b.rings / (double) b.hits);
        b.tally.text(Text.mm("<b><text>" + b.name + "</b>\n<muted>命中 <white>" + b.hits + "/" + b.shots + " <muted>("
                + percent(b) + ") · 平均 <white>" + avg + " <muted>环\n<muted>上一发 " + lastShot(b)));
    }

    private static String lastShot(Board b) {
        return switch (b.last) {
            case -2 -> "<dim>-";
            case -1 -> "<danger>脱靶";
            case 0 -> "<accent>未上环";
            default -> "<accent>" + b.last + " 环";
        };
    }

    private static String percent(Board b) {
        return b.shots == 0 ? "-" : Math.round(100.0 * b.hits / b.shots) + "%";
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
