package net.infnetwork.snowball.craftov.weapon;

import io.papermc.paper.raytracing.BlockCollisionMode;
import io.papermc.paper.raytracing.RayTraceTarget;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.BodyPart;
import net.infnetwork.snowball.craftov.item.Keys;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/** Hitscan bullets shared by players and AI. */
public final class Ballistics {

    /** Pieces a round's path can have: it goes on through a block (see {@link #impact}) at most twice. */
    private static final int MAX_SEGMENTS = 3;

    private final CraftovPlugin plugin;

    public Ballistics(CraftovPlugin plugin) {
        this.plugin = plugin;
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
     * Fires one pellet from {@code eye} along {@code dir}. Returns the entity hit, if any.
     *
     * <p>The round's path is traced in up to {@link #MAX_SEGMENTS} pieces: glass it shatters on the way
     * lets it fly on with a little less damage, so a window costs a round some punch instead of the
     * whole shot. The tracer, the replay and the fly-by crack all follow it to where it finally stops.
     */
    public @Nullable LivingEntity fire(LivingEntity shooter, Location eye, Vector dir, GunSpec gun,
                                       @Nullable AmmoSpec ammo, String weaponName, boolean tracer) {
        World world = eye.getWorld();
        Predicate<Entity> filter = entity -> entity instanceof LivingEntity living
                && entity != shooter
                && !living.isDead()
                && !(entity instanceof org.bukkit.entity.ArmorStand)
                && !entity.getPersistentDataContainer().has(Keys.ENTITY_ROLE)
                && !(entity instanceof Player p && (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || !plugin.raids().canBeShot(p)))
                && plugin.raids().canDamage(shooter, living);
        Vector from = eye.toVector();
        double travelled = 0;
        double keep = 1.0;
        List<Block> passed = new ArrayList<>(MAX_SEGMENTS - 1);
        Vector end = null;
        boolean hitAnything = false;
        LivingEntity hitEntity = null;
        for (int segment = 1; end == null; segment++) {
            double left = gun.maxRange() - travelled;
            Location start = from.toLocation(world);
            // Blocks stop a round where they are drawn (their outline), not at their collision box: a fence
            // or wall collides 1.5 blocks up but is drawn 1 high, which left an invisible half-block shield
            // on top of every railing. Not the visual shape either, which is empty for glass. Whatever a
            // player walks through (grass, flowers, signs) is ignored, and so are the blocks already passed.
            RayTraceResult result = left <= 0.01 ? null : world.rayTrace(b -> b.start(start).direction(dir).maxDistance(left)
                    .fluidCollisionMode(FluidCollisionMode.NEVER).blockCollisionMode(BlockCollisionMode.OUTLINE)
                    .blockFilter(block -> !block.isPassable() && !passed.contains(block)).entityFilter(filter)
                    .targets(RayTraceTarget.ENTITY, RayTraceTarget.BLOCK));
            if (result == null) {
                end = from.clone().add(dir.clone().multiply(Math.max(0, Math.min(left, 60 - travelled))));
                break;
            }
            hitAnything = true;
            Vector at = result.getHitPosition();
            double distance = travelled + from.distance(at);
            if (result.getHitEntity() instanceof LivingEntity living) {
                hitEntity = living;
                end = at;
                Location point = at.toLocation(world);
                BodyPart part = BodyPart.of(living, at);
                plugin.damage().bullet(shooter, living, part, keep < 1.0 ? gun.withDamage(gun.damage() * keep) : gun, ammo, distance, weaponName);
                if (plugin.drones().isDrone(living)) {
                    world.spawnParticle(Particle.ELECTRIC_SPARK, point, 8, 0.1, 0.1, 0.1, 0.3);
                } else {
                    plugin.weapons().fx().body(living, point, dir);
                }
                break;
            }
            Block block = result.getHitBlock();
            double through = block == null ? 0 : impact(block, at.toLocation(world),
                    result.getHitBlockFace() == null ? null : result.getHitBlockFace().getDirection(), dir);
            if (through <= 0 || segment >= MAX_SEGMENTS) {
                end = at;
                break;
            }
            // On through the block from where the round met it; the block itself is skipped from here on.
            passed.add(block);
            keep *= through;
            travelled = distance;
            from = at;
        }
        Location stop = end.toLocation(world);
        if (shooter instanceof Player && plugin.getConfig().getBoolean("debug", false)) {
            // Where each player round landed, for measuring spread and climb against the crosshair.
            plugin.getLogger().info(!hitAnything
                    ? String.format("[debug] %s impact eye=%.3f,%.3f,%.3f hit=none through=%d", shooter.getName(),
                            eye.getX(), eye.getY(), eye.getZ(), passed.size())
                    : String.format("[debug] %s impact eye=%.3f,%.3f,%.3f hit=%.3f,%.3f,%.3f through=%d keep=%.2f target=%s",
                            shooter.getName(), eye.getX(), eye.getY(), eye.getZ(), end.getX(), end.getY(), end.getZ(),
                            passed.size(), keep, hitEntity == null ? "-" : hitEntity.getName()));
        }
        if (tracer) {
            plugin.weapons().fx().tracer(shooter, muzzlePoint(shooter, dir), stop);
        }
        plugin.replays().shot(shooter, muzzlePoint(shooter, dir), stop, gun);
        plugin.weapons().audio().flyby(shooter, eye, stop, hitEntity, gun);
        return hitEntity;
    }

    /**
     * What a block does to a round that meets it at {@code at}. Glass in a raid shatters and lets the
     * round fly on; anything else stops it with a bullet hole, dust and the material's sound. Returns
     * the share of its damage the round keeps going on, or 0 if it stops here.
     */
    private double impact(Block block, Location at, @Nullable Vector normal, Vector dir) {
        World world = at.getWorld();
        Material type = block.getType();
        if (type.name().contains("GLASS") && type != Material.TINTED_GLASS && plugin.raids().isRaidWorld(world)) {
            block.setType(Material.AIR, false);
            world.playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 1f, 1f);
            plugin.weapons().audio().impact(block, at);
            world.spawnParticle(Particle.BLOCK, at, 20, 0.3, 0.3, 0.3, type.createBlockData());
            return Math.max(0, plugin.getConfig().getDouble("combat.penetration.glass-damage", 0.9));
        }
        plugin.weapons().fx().block(block, at, normal, dir);
        plugin.weapons().audio().impact(block, at);
        return 0;
    }

    /** Where the muzzle of a gun held by {@code shooter} roughly is. */
    public static Location muzzlePoint(LivingEntity shooter, Vector dir) {
        Location eye = shooter.getEyeLocation();
        Vector flat = new Vector(dir.getX(), 0, dir.getZ());
        Vector right = flat.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : flat.getCrossProduct(new Vector(0, 1, 0)).normalize();
        return eye.clone().add(dir.clone().multiply(0.9)).add(right.multiply(0.22)).add(0, -0.18, 0);
    }

    public static void muzzle(LivingEntity shooter, Vector dir) {
        Location tip = muzzlePoint(shooter, dir);
        shooter.getWorld().spawnParticle(Particle.SMOKE, tip, 2, 0.02, 0.02, 0.02, 0.01);
        shooter.getWorld().spawnParticle(Particle.DUST, tip, 2, 0.03, 0.03, 0.03, 0,
                new Particle.DustOptions(Color.fromRGB(255, 200, 90), 0.8f));
    }
}
