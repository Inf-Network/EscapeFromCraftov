package net.infnetwork.snowball.craftov.weapon;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.HitZone;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.Keys;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/** Grenades: thrown as bouncing item entities, then detonate after their fuse. */
public final class ThrowableService implements Listener {

    private final CraftovPlugin plugin;

    public ThrowableService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onThrow(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) {
            return;
        }
        ItemDef def = plugin.items().defOf(event.getItem());
        if (def == null || def.throwable() == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!plugin.raids().canFire(player) || plugin.lobby().isLobby(player.getWorld())) {
            return;
        }
        if (player.hasCooldown(event.getItem())) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemStack thrownStack = hand.clone();
        thrownStack.setAmount(1);
        hand.setAmount(hand.getAmount() - 1);
        player.setCooldown(thrownStack, 20);
        Vector velocity = player.getEyeLocation().getDirection().multiply(player.isSneaking() ? 0.6 : 1.15).add(new Vector(0, 0.15, 0));
        Item item = player.getWorld().dropItem(player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.4)), thrownStack, i -> {
            i.setPickupDelay(Integer.MAX_VALUE);
            i.setVelocity(velocity);
            i.setPersistent(false);
            i.setUnlimitedLifetime(false);
            i.getPersistentDataContainer().set(Keys.ENTITY_ROLE, PersistentDataType.STRING, "grenade");
        });
        player.getWorld().playSound(player.getLocation(), "craftov:throw.pin", SoundCategory.PLAYERS, 1f, 1f);
        ThrowableSpec spec = def.throwable();
        Bukkit.getScheduler().runTaskLater(plugin, () -> detonate(player, item, spec, def.name()), spec.fuseTicks());
    }

    private void detonate(Player thrower, Item item, ThrowableSpec spec, String name) {
        if (!item.isValid()) {
            return;
        }
        Location at = item.getLocation().add(0, 0.2, 0);
        item.remove();
        switch (spec.type()) {
            case FRAG -> frag(thrower, at, spec, name);
            case SMOKE -> smoke(at, spec);
            case FLASH -> flash(at, spec);
        }
        plugin.ai().alert(at, 40, thrower);
    }

    private void frag(Player thrower, Location at, ThrowableSpec spec, String name) {
        at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at, 30, 1.2, 0.6, 1.2, 0.05);
        at.getWorld().playSound(at, "craftov:throw.explode", SoundCategory.PLAYERS, 6f, 1f);
        for (LivingEntity entity : at.getWorld().getNearbyLivingEntities(at, spec.radius())) {
            if (entity.getPersistentDataContainer().has(Keys.ENTITY_ROLE)) {
                continue;
            }
            if (entity instanceof Player p && !plugin.raids().canBeShot(p)) {
                continue;
            }
            Location target = entity.getLocation().add(0, entity.getHeight() / 2, 0);
            Vector to = target.toVector().subtract(at.toVector());
            double distance = to.length();
            RayTraceResult wall = at.getWorld().rayTraceBlocks(at, to.clone().normalize(), Math.max(0.1, distance), org.bukkit.FluidCollisionMode.NEVER, true);
            if (wall != null && wall.getHitBlock() != null) {
                continue;
            }
            double damage = spec.damage() * Math.max(0.1, 1 - distance / spec.radius());
            plugin.damage().recordHit(entity, thrower, name, HitZone.TORSO);
            plugin.damage().deal(entity, entity == thrower ? null : thrower, damage);
            entity.setVelocity(entity.getVelocity().add(to.normalize().multiply(0.4)));
        }
    }

    private void smoke(Location at, ThrowableSpec spec) {
        at.getWorld().playSound(at, "craftov:throw.smoke", SoundCategory.PLAYERS, 2f, 1f);
        new BukkitRunnable() {
            int ticks;

            @Override
            public void run() {
                if (ticks++ > 60) {
                    cancel();
                    return;
                }
                at.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at, 30, spec.radius() * 0.45, 1.2, spec.radius() * 0.45, 0.005, null, true);
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    /**
     * Players and soldiers follow the same rules: nothing through a wall, much less with the blast behind
     * them, weaker towards the edge of the radius. Players get a white screen that fades out and ringing
     * ears; soldiers are dazzled (they keep fighting, but shoot wide), even right after a strobe.
     */
    private void flash(Location at, ThrowableSpec spec) {
        // The flash particle takes a colour on 1.21.11; without one Paper throws and the grenade does nothing.
        at.getWorld().spawnParticle(Particle.FLASH, at, 2, 0, 0, 0, 0, org.bukkit.Color.WHITE);
        at.getWorld().playSound(at, "craftov:throw.flash", SoundCategory.PLAYERS, 4f, 1f);
        var config = plugin.getConfig();
        for (LivingEntity entity : at.getWorld().getNearbyLivingEntities(at, spec.radius())) {
            boolean player = entity instanceof Player p && p.getGameMode() != org.bukkit.GameMode.SPECTATOR;
            if (!player && !plugin.ai().isAi(entity)) {
                continue;
            }
            FlashHit hit = flashHit(at, entity, spec.radius());
            if (hit == null) {
                continue;
            }
            if (player) {
                int facing = config.getInt("combat.flashbang.facing-ticks", 80);
                int full = hit.facing() ? facing : config.getInt("combat.flashbang.away-ticks", 30);
                int ticks = (int) Math.max(config.getInt("combat.flashbang.min-ticks", 20), Math.round(full * hit.falloff()));
                Player flashed = (Player) entity;
                plugin.hud().flash(flashed, ticks);
                float volume = (float) Math.min(1.0, 0.3 + 0.7 * ticks / Math.max(1.0, facing));
                flashed.playSound(flashed.getLocation(), "craftov:status.tinnitus", SoundCategory.PLAYERS, volume, 1f);
            } else {
                int full = hit.facing() ? config.getInt("ai.dazzle.flash-facing-ticks", 40)
                        : config.getInt("ai.dazzle.flash-away-ticks", 15);
                plugin.ai().dazzle(entity, (int) Math.round(full * hit.falloff()), true);
            }
        }
    }

    /** Whether someone was looking at the blast, and how much of its strength reaches them (0..1]. */
    private record FlashHit(boolean facing, double falloff) {
    }

    /**
     * How a flashbang going off at {@code at} reaches someone: null if they are out of range or a block
     * stands between the blast and their eyes. Full strength at the blast, fading to
     * combat.flashbang.edge-strength at the edge of the radius.
     */
    private @Nullable FlashHit flashHit(Location at, LivingEntity entity, double radius) {
        Location eye = entity.getEyeLocation();
        if (eye.getWorld() != at.getWorld()) {
            return null;
        }
        Vector toEye = eye.toVector().subtract(at.toVector());
        double distance = toEye.length();
        if (distance > radius) {
            return null;
        }
        boolean facing = true;
        if (distance > 0.05) {
            Vector dir = toEye.clone().multiply(1 / distance);
            RayTraceResult wall = at.getWorld().rayTraceBlocks(at, dir, distance, org.bukkit.FluidCollisionMode.NEVER, true);
            if (wall != null && wall.getHitBlock() != null) {
                return null;
            }
            facing = eye.getDirection().dot(dir.multiply(-1)) > 0.3;
        }
        double edge = plugin.getConfig().getDouble("combat.flashbang.edge-strength", 0.4);
        return new FlashHit(facing, 1 - (1 - edge) * Math.min(1, distance / Math.max(0.1, radius)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickupGrenade(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (event.getItem().getPersistentDataContainer().has(Keys.ENTITY_ROLE)) {
            event.setCancelled(true);
        }
    }

}
