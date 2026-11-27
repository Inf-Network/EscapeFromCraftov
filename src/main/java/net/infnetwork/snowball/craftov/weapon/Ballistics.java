package net.infnetwork.snowball.craftov.weapon;

import io.papermc.paper.raytracing.BlockCollisionMode;
import io.papermc.paper.raytracing.RayTraceTarget;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.BodyPart;
import net.infnetwork.snowball.craftov.combat.HitboxHistory;
import net.infnetwork.snowball.craftov.item.Keys;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/** Hitscan bullets shared by players and AI. */
public final class Ballistics {

    /** Layers a round goes on through at most: glass it shatters, or a thin layer its penetration beats. */
    private static final int MAX_LAYERS = 2;
    /** Blocks a round's path is traced to at most, each block of a layer counted. */
    private static final int MAX_TRACES = 12;
    /** Slack when matching points on block faces. */
    private static final double EPS = 1e-4;
    /** Blocks drawn see-through: glass of every kind but tinted, glass panes and iron bars. */
    private static final Set<Material> SEE_THROUGH = EnumSet.noneOf(Material.class);

    static {
        for (Material type : Material.values()) {
            if (!type.name().startsWith("LEGACY_") && type.isBlock()
                    && (type.name().contains("GLASS") && type != Material.TINTED_GLASS || type == Material.IRON_BARS)) {
                SEE_THROUGH.add(type);
            }
        }
    }

    private final CraftovPlugin plugin;
    private final BlockPenetration penetration;

    public Ballistics(CraftovPlugin plugin) {
        this.plugin = plugin;
        this.penetration = new BlockPenetration(plugin.getLogger());
    }

    /** Random direction inside a cone of {@code spreadDegrees} around {@code dir}. */
    public static Vector spread(Vector dir, double spreadDegrees) {
        if (spreadDegrees <= 0.001) {
            return dir.clone().normalize();
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double angle = Math.toRadians(spreadDegrees) * Math.sqrt(rng.nextDouble());
        double theta = rng.nextDouble() * Math.PI * 2;
        Vector forward = dir.clone().normalize();
        Vector up = Math.abs(forward.getY()) > 0.99 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector right = forward.getCrossProduct(up).normalize();
        Vector trueUp = right.getCrossProduct(forward).normalize();
        return forward.multiply(Math.cos(angle))
                .add(right.multiply(Math.sin(angle) * Math.cos(theta)))
                .add(trueUp.multiply(Math.sin(angle) * Math.sin(theta)))
                .normalize();
    }

    /**
     * Fires one pellet from {@code eye} along {@code dir}. Returns the entity hit, if any. A soldier's
     * round stops in a fellow soldier (shield and all) without hurting it, rather than passing through.
     *
     * <p>The round's path is traced block by block: glass it shatters on the way lets it fly on with a
     * little less damage, so a window costs a round some punch instead of the whole shot, and so does a
     * thin layer its penetration is enough for ({@link BlockPenetration}), at most {@link #MAX_LAYERS}
     * of them. A layer is a run of blocks with the same rule that the round goes from one straight into
     * the next: one plank wall crossed at an angle leaves one plank through its side into the next, and
     * is still one wall, paid for once, as long as the path through it stays within
     * {@code combat.penetration.layer-depth} (two walls back to back are 2 blocks deep square-on). The
     * tracer, the replay and the fly-by crack all follow the round to where it finally stops.
     */
    public @Nullable LivingEntity fire(LivingEntity shooter, Location eye, Vector dir, GunSpec gun,
                                       @Nullable AmmoSpec ammo, String weaponName, boolean tracer) {
        World world = eye.getWorld();
        boolean soldier = plugin.ai().isAi(shooter);
        Predicate<Entity> filter = entity -> entity instanceof LivingEntity living
                && entity != shooter
                && !living.isDead()
                && !(entity instanceof org.bukkit.entity.ArmorStand)
                && !entity.getPersistentDataContainer().has(Keys.ENTITY_ROLE)
                && !(entity instanceof Player p && (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || !plugin.raids().canBeShot(p)))
                && (plugin.raids().canDamage(shooter, living) || soldier && plugin.ai().isAi(living));
        // A player's round meets everyone where that player's screen showed them (HitboxHistory).
        HitboxHistory.Rewind rewind = plugin.hitboxes().rewind(shooter);
        // How far back the entity hit was looked for (a sliding one less far), for the debug line.
        int rewound = rewind.millis();
        Vector from = eye.toVector();
        double travelled = 0;
        double keep = 1.0;
        // Penetration left for blocks; armour still meets the round's full rating (DamageService).
        int pen = ammo == null ? 1 : ammo.penetration();
        double layerDepth = plugin.getConfig().getDouble("combat.penetration.layer-depth", 1.8);
        List<Block> passed = new ArrayList<>();
        int layers = 0;
        // The layer the round is going through: its rule, where the round entered it, and where the round
        // comes out of the last of its blocks it went into (null once it is in the open again).
        BlockPenetration.Rule layerRule = null;
        Vector layerEntry = null;
        Vector layerExit = null;
        Vector end = null;
        // Where the round stops in the kill replay, which shows everyone where the server had them.
        Vector replayEnd = null;
        boolean hitAnything = false;
        double dealt = 0;
        String partHit = "-";
        BodyPart bodyPart = BodyPart.CHEST;
        LivingEntity hitEntity = null;
        for (int trace = 1; end == null; trace++) {
            double left = gun.maxRange() - travelled;
            Location start = from.toLocation(world);
            // Blocks stop a round where they are drawn (their outline), not at their collision box: a fence
            // or wall collides 1.5 blocks up but is drawn 1 high, which left an invisible half-block shield
            // on top of every railing. Not the visual shape either, which is empty for glass. Whatever a
            // player walks through (grass, flowers, signs) is ignored, and so are the blocks already passed.
            RayTraceResult result = left <= 0.01 ? null : world.rayTrace(b -> b.start(start).direction(dir).maxDistance(left)
                    .fluidCollisionMode(FluidCollisionMode.NEVER).blockCollisionMode(BlockCollisionMode.OUTLINE)
                    .blockFilter(block -> !block.isPassable() && !passed.contains(block))
                    .targets(RayTraceTarget.BLOCK));
            double reach = result == null ? left : from.distance(result.getHitPosition());
            HitboxHistory.Hit hit = plugin.hitboxes().trace(world, from, dir, reach, filter, rewind);
            if (hit != null) {
                hitAnything = true;
                LivingEntity living = hit.entity();
                rewound = hit.rewind();
                end = hit.point();
                replayEnd = hit.body();
                if (!plugin.raids().canDamage(shooter, living)) {
                    // A fellow soldier in the line of fire: the round ends there and does nothing.
                    break;
                }
                hitEntity = living;
                // The hit zone is read on the body the shooter saw (crouched or standing then); the wound
                // and the blood go on the body where it stands now.
                Location point = hit.body().toLocation(world);
                BodyPart part = hit.part() != null ? hit.part() : BodyPart.of(living, hit.box(), hit.eyeHeight(), hit.point(), dir);
                bodyPart = part;
                partHit = part.key();
                dealt = plugin.damage().bullet(shooter, living, part, keep < 1.0 ? gun.withDamage(gun.damage() * keep) : gun, ammo,
                        travelled + hit.distance(), weaponName);
                if (shooter instanceof Player p && plugin.lobby().isTarget(living)) {
                    plugin.training().onTargetHit(p, living);
                }
                if (plugin.drones().isDrone(living)) {
                    world.spawnParticle(Particle.ELECTRIC_SPARK, point, 8, 0.1, 0.1, 0.1, 0.3);
                } else if (!plugin.range().targets().impact(living, point)) {
                    plugin.weapons().fx().body(living, point, dir);
                }
                break;
            }
            if (result == null) {
                // Into the open, perhaps through glass on the way: it lands nowhere.
                hitAnything = false;
                end = from.clone().add(dir.clone().multiply(Math.max(0, Math.min(left, 60 - travelled))));
                break;
            }
            hitAnything = true;
            Vector at = result.getHitPosition();
            double distance = travelled + reach;
            Block block = result.getHitBlock();
            if (block == null) {
                end = at;
                break;
            }
            Location point = at.toLocation(world);
            Vector normal = result.getHitBlockFace() == null ? null : result.getHitBlockFace().getDirection();
            boolean glass = shatters(block);
            BlockPenetration.Rule rule = glass ? null : penetration.of(plugin.getConfig(), block.getType());
            double inside = rule == null ? 0 : inside(block, at, dir);
            if (rule != null && rule.equals(layerRule) && layerExit != null && layerExit.distanceSquared(at) < EPS * EPS) {
                // Out of the layer's last block straight into another of the same: still the same layer,
                // with no new hole and nothing more to pay, unless the path through it gets too long.
                if (layerEntry.distance(at) + inside > layerDepth || trace >= MAX_TRACES) {
                    end = at;
                    break;
                }
            } else {
                double through;
                if (glass) {
                    through = shatter(block, point);
                } else {
                    plugin.weapons().fx().block(block, point, normal, dir);
                    plugin.weapons().audio().impact(block, point);
                    if (rule != null && pen >= rule.level() && inside <= layerDepth) {
                        through = rule.damage();
                        pen -= rule.level();
                    } else {
                        through = 0;
                    }
                }
                if (through <= 0 || layers >= MAX_LAYERS || trace >= MAX_TRACES) {
                    end = at;
                    break;
                }
                layers++;
                keep *= through;
                layerRule = rule;
                layerEntry = at;
            }
            // On through the block from where the round met it; the block itself is skipped from here on.
            layerExit = rule == null ? null : at.clone().add(dir.clone().multiply(inside));
            passed.add(block);
            travelled = distance;
            from = at;
        }
        // The tracer and the fly-by crack end where the shooter saw the round land.
        Location stop = end.toLocation(world);
        if (shooter instanceof Player && plugin.getConfig().getBoolean("debug", false)) {
            // Where each player round landed, for measuring spread and climb against the crosshair.
            plugin.getLogger().info(!hitAnything
                    ? String.format("[debug] %s impact eye=%.3f,%.3f,%.3f hit=none through=%d rewind=%dms", shooter.getName(),
                            eye.getX(), eye.getY(), eye.getZ(), layers, rewound)
                    : String.format("[debug] %s impact eye=%.3f,%.3f,%.3f hit=%.3f,%.3f,%.3f through=%d keep=%.2f target=%s part=%s damage=%.1f rewind=%dms",
                            shooter.getName(), eye.getX(), eye.getY(), eye.getZ(), end.getX(), end.getY(), end.getZ(),
                            layers, keep, hitEntity == null ? "-" : hitEntity.getName(), partHit, dealt, rewound));
        }
        if (shooter instanceof Player p && plugin.range().isIn(p)) {
            plugin.range().targets().shot(p, hitEntity, bodyPart, dealt);
        }
        if (tracer) {
            plugin.weapons().fx().tracer(shooter, muzzlePoint(shooter, dir), stop);
        }
        plugin.replays().shot(shooter, muzzlePoint(shooter, dir), replayEnd == null ? stop : replayEnd.toLocation(world), gun);
        plugin.weapons().audio().flyby(shooter, eye, stop, hitEntity, gun);
        return hitEntity;
    }

    /**
     * Whether a soldier (or a drone) with its eyes at {@code from} can see {@code to}: blocks count by
     * their outline, as they do for rounds ({@link #fire}), so a fence or wall hides what is behind it up
     * to the 1 block it is drawn, not the 1.5 it collides at, and glass, glass panes and iron bars are
     * seen through. Vanilla line of sight tested collision boxes, so once rounds went by the outline a
     * player could shoot over a railing, a parapet or through bars at a soldier who could not see back.
     * Leaves hide what is behind them unless {@code foliage}: a soldier that already knows where you
     * are sees you in a bush, and its rounds go through the leaves as yours do.
     */
    public static boolean sees(Location from, Location to, boolean foliage) {
        World world = from.getWorld();
        if (world == null || world != to.getWorld()) {
            return false;
        }
        Vector line = to.toVector().subtract(from.toVector());
        double length = line.length();
        if (length < 1e-6) {
            return true;
        }
        Vector dir = line.multiply(1 / length);
        RayTraceResult result = world.rayTrace(b -> b.start(from).direction(dir).maxDistance(length)
                .fluidCollisionMode(FluidCollisionMode.NEVER).blockCollisionMode(BlockCollisionMode.OUTLINE)
                .blockFilter(block -> !block.isPassable() && !seeThrough(block.getType(), foliage))
                .targets(RayTraceTarget.BLOCK));
        return result == null;
    }

    private static boolean seeThrough(Material type, boolean foliage) {
        return SEE_THROUGH.contains(type) || foliage && Tag.LEAVES.isTagged(type);
    }

    /**
     * How far a round that met {@code block} at {@code at} runs inside the block's shape along
     * {@code dir} before it comes out again; 0 if it only grazes it.
     */
    private static double inside(Block block, Vector at, Vector dir) {
        double[] origin = {at.getX(), at.getY(), at.getZ()};
        double[] step = {dir.getX(), dir.getY(), dir.getZ()};
        double out = 0;
        // The shape's boxes are relative to the block.
        for (BoundingBox part : block.getCollisionShape().getBoundingBoxes()) {
            double[] min = {part.getMinX() + block.getX(), part.getMinY() + block.getY(), part.getMinZ() + block.getZ()};
            double[] max = {part.getMaxX() + block.getX(), part.getMaxY() + block.getY(), part.getMaxZ() + block.getZ()};
            double enter = Double.NEGATIVE_INFINITY;
            double leave = Double.POSITIVE_INFINITY;
            boolean miss = false;
            for (int axis = 0; axis < 3 && !miss; axis++) {
                if (Math.abs(step[axis]) < 1e-9) {
                    miss = origin[axis] < min[axis] - EPS || origin[axis] > max[axis] + EPS;
                    continue;
                }
                double t1 = (min[axis] - origin[axis]) / step[axis];
                double t2 = (max[axis] - origin[axis]) / step[axis];
                enter = Math.max(enter, Math.min(t1, t2));
                leave = Math.min(leave, Math.max(t1, t2));
            }
            // Only a part the round starts in (or on) and runs on into.
            if (!miss && enter <= EPS && leave > EPS && leave >= enter) {
                out = Math.max(out, leave);
            }
        }
        return out;
    }

    /**
     * Whether a round with {@code pen} penetration gets through {@code block} on its own: glass in a
     * raid it shatters, or a block in combat.penetration.blocks it has the level for. Soldiers ask it
     * of the see-through blocks on their line of fire, so they shoot back through the bars and the
     * leaves a player's rounds go through.
     */
    public boolean passes(Block block, int pen) {
        if (shatters(block)) {
            return true;
        }
        BlockPenetration.Rule rule = penetration.of(plugin.getConfig(), block.getType());
        return rule != null && pen >= rule.level();
    }

    /** Glass in a raid, other than tinted glass, breaks under any round. */
    private boolean shatters(Block block) {
        Material type = block.getType();
        return type.name().contains("GLASS") && type != Material.TINTED_GLASS && plugin.raids().isRaidWorld(block.getWorld());
    }

    /** Breaks the glass a round met at {@code at}. Returns the share of its damage the round keeps going on. */
    private double shatter(Block block, Location at) {
        World world = at.getWorld();
        Material type = block.getType();
        block.setType(Material.AIR, false);
        world.playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 1f, 1f);
        plugin.weapons().audio().impact(block, at);
        world.spawnParticle(Particle.BLOCK, at, 20, 0.3, 0.3, 0.3, type.createBlockData());
        return Math.max(0, plugin.getConfig().getDouble("combat.penetration.glass-damage", 0.9));
    }

    /** Where the muzzle of a gun held by {@code shooter} roughly is. */
    public static Location muzzlePoint(LivingEntity shooter, Vector dir) {
        Location eye = shooter.getEyeLocation();
        Vector flat = new Vector(dir.getX(), 0, dir.getZ());
        Vector right = flat.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : flat.getCrossProduct(new Vector(0, 1, 0)).normalize();
        return eye.clone().add(dir.clone().multiply(0.9)).add(right.multiply(0.22)).add(0, -0.18, 0);
    }

    public static void muzzle(LivingEntity shooter, Vector dir) {
        muzzle(shooter, dir, true);
    }

    /**
     * Muzzle smoke and sparks where the muzzle is, for everyone within 32 blocks. {@code shooterSees}
     * false leaves the shooter out: a player firing on a sprint runs into their own smoke within a few
     * ticks, and up against the camera it covers the middle of the screen in big blocky puffs (the
     * gun's own flash in the item model still shows); aiming, the puffs sit right beside the sight and
     * burst over it with every round (issue #118). Sent unforced, as World.spawnParticle sends
     * them: a builder forces by default, which would override each viewer's particle setting and widen
     * the reach to 512 blocks; unforced, the server also keeps them to 32 blocks of each receiver.
     */
    public static void muzzle(LivingEntity shooter, Vector dir, boolean shooterSees) {
        Location tip = muzzlePoint(shooter, dir);
        List<Player> receivers = new ArrayList<>(tip.getNearbyPlayers(32));
        if (!shooterSees) {
            receivers.remove(shooter);
        }
        Particle.SMOKE.builder().location(tip).count(2).offset(0.02, 0.02, 0.02).extra(0.01).force(false)
                .receivers(receivers).spawn();
        Particle.DUST.builder().location(tip).count(2).offset(0.03, 0.03, 0.03).extra(0).force(false)
                .data(new Particle.DustOptions(Color.fromRGB(255, 200, 90), 0.8f)).receivers(receivers).spawn();
    }
}
