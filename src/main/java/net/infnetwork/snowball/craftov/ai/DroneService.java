package net.infnetwork.snowball.craftov.ai;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.map.MapDef;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Enemy recon drones. A drone cruises between the map's containers and extracts about ten blocks up;
 * a player it can see within range is marked: it hovers over them beeping, they are told, and every
 * soldier within 60 blocks is sent their way. It can be shot down. The hit box is an invisible bee
 * with no AI (moved by hand) and the look is an item display of a quadcopter riding along.
 */
public final class DroneService implements Listener {

    private static final double SPEED = 0.35;
    private static final double SCAN_RANGE = 26;
    private static final double ALTITUDE = 10;

    private static final class Drone {
        final Bee body;
        final ItemDisplay look;
        final Raid raid;
        Location target;
        @Nullable UUID tracking;
        long trackUntil;
        final Map<UUID, Long> spotted = new HashMap<>();

        Drone(Bee body, ItemDisplay look, Raid raid, Location target) {
            this.body = body;
            this.look = look;
            this.raid = raid;
            this.target = target;
        }
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, Drone> drones = new HashMap<>();

    public DroneService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    /** Launches a drone from the map's edge; returns where it took off, or null. */
    public @Nullable Location launch(Raid raid) {
        MapDef map = raid.map;
        if (map.spawns().isEmpty()) {
            return null;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location from = map.spawns().get(rng.nextInt(map.spawns().size())).pos().at(raid.world).add(0, ALTITUDE, 0);
        Bee body = raid.world.spawn(from, Bee.class, b -> {
            b.setAI(false);
            b.setGravity(false);
            b.setInvisible(true);
            b.setSilent(true);
            b.setPersistent(false);
            b.setRemoveWhenFarAway(false);
            var health = b.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(24);
            }
            b.setHealth(24);
            b.getPersistentDataContainer().set(Keys.key("drone"), PersistentDataType.BYTE, (byte) 1);
            b.customName(Text.mm("<#FF8A80>侦察无人机"));
            b.setCustomNameVisible(false);
        });
        ItemStack model = ItemStack.of(Material.PAPER);
        model.setData(DataComponentTypes.ITEM_MODEL, Key.key("craftov", "fx/drone"));
        ItemDisplay look = raid.world.spawn(from, ItemDisplay.class, d -> {
            d.setItemStack(model);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(false);
            d.setTeleportDuration(2);
            d.setViewRange(2f);
        });
        Drone drone = new Drone(body, look, raid, waypoint(raid));
        drones.put(body.getUniqueId(), drone);
        return from;
    }

    private static Location waypoint(Raid raid) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<Location> spots = new ArrayList<>();
        for (MapDef.ContainerDef c : raid.map.containers()) {
            spots.add(c.pos().at(raid.world));
        }
        for (MapDef.ExtractDef e : raid.map.extracts()) {
            spots.add(e.pos().at(raid.world));
        }
        Location at = spots.isEmpty()
                ? new Location(raid.world, rng.nextDouble(raid.map.minX(), raid.map.maxX()), 64, rng.nextDouble(raid.map.minZ(), raid.map.maxZ()))
                : spots.get(rng.nextInt(spots.size()));
        int ground = raid.world.getHighestBlockYAt(at.getBlockX(), at.getBlockZ());
        return new Location(raid.world, at.getX(), ground + ALTITUDE, at.getZ());
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        for (Iterator<Drone> it = drones.values().iterator(); it.hasNext(); ) {
            Drone drone = it.next();
            if (!drone.body.isValid() || drone.raid.state != Raid.State.RUNNING) {
                drone.look.remove();
                drone.body.remove();
                it.remove();
                continue;
            }
            Player tracked = drone.tracking == null ? null : Bukkit.getPlayer(drone.tracking);
            if (tracked != null && (now > drone.trackUntil || tracked.getWorld() != drone.raid.world)) {
                drone.tracking = null;
                tracked = null;
            }
            if (tracked != null) {
                drone.target = tracked.getLocation().add(0, 7, 0);
            }
            Location at = drone.body.getLocation();
            Vector to = drone.target.toVector().subtract(at.toVector());
            if (to.length() < 1.2 && tracked == null) {
                drone.target = waypoint(drone.raid);
            } else if (to.lengthSquared() > 0.04) {
                Vector step = to.clone().normalize().multiply(Math.min(SPEED, to.length()));
                Location next = at.clone().add(step);
                float yaw = (float) Math.toDegrees(Math.atan2(-step.getX(), step.getZ()));
                next.setYaw(yaw);
                drone.body.teleport(next);
                Location show = next.clone().add(0, -0.1, 0);
                show.setPitch(0);
                drone.look.teleport(show);
            }
            if (now % 20 == 0) {
                drone.raid.world.playSound(drone.body.getLocation(), "craftov:event.drone", SoundCategory.HOSTILE, 2.2f, 1f);
            }
            if (now % 10 == 0) {
                scan(drone, now);
            }
        }
    }

    private void scan(Drone drone, long now) {
        Location eye = drone.body.getLocation();
        World world = eye.getWorld();
        for (Player player : drone.raid.online()) {
            RaidPlayer rp = drone.raid.player(player.getUniqueId());
            if (rp == null || rp.state != RaidPlayer.State.ALIVE || player.getWorld() != world) {
                continue;
            }
            Location target = player.getEyeLocation();
            double dx = target.getX() - eye.getX();
            double dz = target.getZ() - eye.getZ();
            if (dx * dx + dz * dz > SCAN_RANGE * SCAN_RANGE) {
                continue;
            }
            Vector to = target.toVector().subtract(eye.toVector());
            RayTraceResult wall = world.rayTraceBlocks(eye, to.clone().normalize(), to.length() - 0.3, FluidCollisionMode.NEVER, true);
            if (wall != null && wall.getHitBlock() != null) {
                continue;
            }
            Long last = drone.spotted.get(player.getUniqueId());
            drone.tracking = player.getUniqueId();
            drone.trackUntil = now + 20L * 8;
            plugin.ai().alert(player.getLocation(), 60, null);
            world.playSound(eye, "craftov:extract.beep", SoundCategory.HOSTILE, 2f, 1.6f);
            if (last == null || now - last > 20L * 20) {
                drone.spotted.put(player.getUniqueId(), now);
                player.sendActionBar(Text.mm("<#FF4D4D>⚠ 你被侦察无人机发现了！<#9AA0A6>附近的敌兵正赶过来"));
                player.playSound(player.getLocation(), "craftov:ai.spotted", SoundCategory.MASTER, 1f, 1.2f);
            }
            return;
        }
    }

    public boolean isDrone(org.bukkit.entity.@Nullable Entity entity) {
        return entity != null && drones.containsKey(entity.getUniqueId());
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Drone drone = drones.remove(event.getEntity().getUniqueId());
        if (drone == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        Location at = drone.body.getLocation();
        drone.look.remove();
        at.getWorld().spawnParticle(Particle.EXPLOSION, at, 2, 0.2, 0.2, 0.2, 0);
        at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at, 15, 0.3, 0.3, 0.3, 0.02);
        at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 20, 0.4, 0.3, 0.4, 0.3);
        at.getWorld().playSound(at, "craftov:throw.explode", SoundCategory.HOSTILE, 2f, 1.5f);
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            killer.sendActionBar(Text.mm("<#5BD16B>✈ 击落侦察无人机"));
        }
    }

    public void shutdown() {
        for (Drone drone : drones.values()) {
            drone.look.remove();
            drone.body.remove();
        }
        drones.clear();
    }
}
