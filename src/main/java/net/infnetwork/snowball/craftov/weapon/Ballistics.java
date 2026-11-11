package net.infnetwork.snowball.craftov.weapon;

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

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/** Hitscan bullets shared by players and AI. */
public final class Ballistics {

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
        RayTraceResult result = world.rayTrace(eye, dir, gun.maxRange(), FluidCollisionMode.NEVER, true, 0.0, filter);
        Location end;
        LivingEntity hitEntity = null;
        if (result == null) {
            end = eye.clone().add(dir.clone().multiply(Math.min(gun.maxRange(), 60)));
        } else {
            end = result.getHitPosition().toLocation(world);
            if (result.getHitEntity() instanceof LivingEntity living) {
                hitEntity = living;
                BodyPart part = BodyPart.of(living, result.getHitPosition());
                double distance = eye.toVector().distance(result.getHitPosition());
                plugin.damage().bullet(shooter, living, part, gun, ammo, distance, weaponName);
                if (plugin.drones().isDrone(living)) {
                    world.spawnParticle(Particle.ELECTRIC_SPARK, end, 8, 0.1, 0.1, 0.1, 0.3);
                } else {
                    plugin.weapons().fx().body(living, end, dir);
                }
            } else if (result.getHitBlock() != null) {
                impact(result.getHitBlock(), end, result.getHitBlockFace() == null ? null : result.getHitBlockFace().getDirection(), dir);
            }
        }
        if (tracer) {
            plugin.weapons().fx().tracer(shooter, muzzlePoint(shooter, dir), end);
        }
        plugin.replays().shot(shooter, muzzlePoint(shooter, dir), end, gun);
        plugin.weapons().audio().flyby(shooter, eye, end, hitEntity, gun);
        return hitEntity;
    }

    private void impact(Block block, Location at, @Nullable Vector normal, Vector dir) {
        World world = at.getWorld();
        Material type = block.getType();
        if (type.name().contains("GLASS") && type != Material.TINTED_GLASS && plugin.raids().isRaidWorld(world)) {
            block.setType(Material.AIR, false);
            world.playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 1f, 1f);
            plugin.weapons().audio().impact(block, at);
            world.spawnParticle(Particle.BLOCK, at, 20, 0.3, 0.3, 0.3, type.createBlockData());
            return;
        }
        plugin.weapons().fx().block(block, at, normal, dir);
        plugin.weapons().audio().impact(block, at);
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
