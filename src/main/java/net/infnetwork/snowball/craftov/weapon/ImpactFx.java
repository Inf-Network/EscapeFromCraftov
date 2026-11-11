package net.infnetwork.snowball.craftov.weapon;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What bullets leave behind: holes on walls (flat item displays that fade out after a while),
 * dust, splinters, sparks or blood at the hit, a light pulse at other players' muzzles and
 * travelling tracer rounds.
 */
public final class ImpactFx {

    private static final int DECAL_LIFE_TICKS = 20 * 30;
    private static final int DECALS_PER_WORLD = 250;
    private static final Color TRACER = Color.fromRGB(255, 206, 128);
    private static final Particle.DustOptions BLOOD = new Particle.DustOptions(Color.fromRGB(120, 10, 10), 1.1f);

    private record Decal(ItemDisplay entity, long expires) {
    }

    private record Pulse(World world, int x, int y, int z, long until, List<UUID> viewers) {
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, Deque<Decal>> decals = new HashMap<>();
    private final List<Pulse> pulses = new ArrayList<>();
    private final Map<UUID, Integer> rounds = new HashMap<>();

    public ImpactFx(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    // ------------------------------------------------------------------ impacts

    /** A bullet hit a block face at {@code at}; normal points out of the face (null if unknown). */
    public void block(Block block, Location at, @Nullable Vector normal, Vector direction) {
        World world = at.getWorld();
        String material = GunAudio.material(block.getType());
        Location puff = normal == null ? at : at.clone().add(normal.clone().multiply(0.06));
        BlockData data = block.getType().isAir() ? Material.STONE.createBlockData() : block.getBlockData();
        switch (material) {
            case "metal" -> {
                world.spawnParticle(Particle.ELECTRIC_SPARK, puff, 7, 0.06, 0.06, 0.06, 0.25);
                world.spawnParticle(Particle.CRIT, puff, 3, 0.03, 0.03, 0.03, 0.15);
            }
            case "wood" -> {
                world.spawnParticle(Particle.BLOCK, puff, 9, 0.06, 0.06, 0.06, data);
                world.spawnParticle(Particle.SMOKE, puff, 1, 0.02, 0.02, 0.02, 0.01);
            }
            case "soft" -> {
                world.spawnParticle(Particle.BLOCK, puff, 12, 0.08, 0.1, 0.08, data);
                world.spawnParticle(Particle.DUST_PLUME, puff, 2, 0.05, 0.05, 0.05, 0.02);
            }
            default -> {
                world.spawnParticle(Particle.BLOCK, puff, 7, 0.05, 0.05, 0.05, data);
                world.spawnParticle(Particle.DUST_PLUME, puff, 3, 0.04, 0.04, 0.04, 0.03);
                world.spawnParticle(Particle.SMOKE, puff, 1, 0.02, 0.02, 0.02, 0.01);
            }
        }
        if (normal != null && !material.equals("soft") && !material.equals("glass") && block.getType().isOccluding()) {
            decal(at, normal, material.equals("metal"));
        }
    }

    /** A bullet hit a body: a spray of blood carried along the bullet's direction. */
    public void body(LivingEntity victim, Location at, Vector direction) {
        World world = at.getWorld();
        world.spawnParticle(Particle.DUST, at, 6, 0.08, 0.08, 0.08, 0, BLOOD);
        Vector d = direction.clone().normalize().multiply(0.35);
        for (int i = 0; i < 4; i++) {
            world.spawnParticle(Particle.BLOCK, at, 0, d.getX(), d.getY() + 0.1, d.getZ(), 1, Material.REDSTONE_BLOCK.createBlockData());
        }
    }

    private void decal(Location at, Vector normal, boolean metal) {
        World world = at.getWorld();
        Vector n = normal.clone().normalize();
        Location place = at.clone().add(n.clone().multiply(0.012));
        place.setYaw(0);
        place.setPitch(0);
        float spin = ThreadLocalRandom.current().nextFloat() * (float) (Math.PI * 2);
        float size = 0.16f + ThreadLocalRandom.current().nextFloat() * 0.05f;
        Quaternionf facing = new Quaternionf().rotationTo(0, 0, 1, (float) n.getX(), (float) n.getY(), (float) n.getZ())
                .rotateZ(spin);
        ItemStack item = ItemStack.of(Material.PAPER);
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key("craftov", metal ? "fx/hole_metal" : "fx/hole"));
        ItemDisplay display = world.spawn(place, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new Transformation(new Vector3f(), facing, new Vector3f(size, size, size), new Quaternionf()));
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(false);
            d.setViewRange(0.35f);
        });
        Deque<Decal> list = decals.computeIfAbsent(world.getUID(), k -> new ArrayDeque<>());
        list.addLast(new Decal(display, Bukkit.getCurrentTick() + DECAL_LIFE_TICKS));
        while (list.size() > DECALS_PER_WORLD) {
            list.removeFirst().entity().remove();
        }
    }

    // ------------------------------------------------------------------ muzzle and tracers

    /** Lights up the muzzle for a moment for everyone but the shooter (their own flash is in the gun model). */
    public void muzzleLight(LivingEntity shooter, Location muzzle) {
        Block block = muzzle.getBlock();
        if (!block.getType().isAir()) {
            return;
        }
        List<UUID> viewers = new ArrayList<>();
        Light light = (Light) Material.LIGHT.createBlockData();
        light.setLevel(11);
        for (Player player : muzzle.getWorld().getPlayers()) {
            if (player != shooter && player.getLocation().distanceSquared(muzzle) < 48 * 48) {
                player.sendBlockChange(block.getLocation(), light);
                viewers.add(player.getUniqueId());
            }
        }
        if (!viewers.isEmpty()) {
            pulses.add(new Pulse(muzzle.getWorld(), block.getX(), block.getY(), block.getZ(), Bukkit.getCurrentTick() + 2, viewers));
        }
    }

    /** One in three rounds is a tracer: a glowing streak flying from the muzzle to where the round ended. */
    public void tracer(LivingEntity shooter, Location from, Location to) {
        int n = rounds.merge(shooter.getUniqueId(), 1, Integer::sum);
        if (n % 3 != 1) {
            return;
        }
        double length = from.distance(to);
        if (length < 2) {
            return;
        }
        int ticks = (int) Math.max(1, Math.min(6, Math.round(length / 28)));
        Vector dir = to.toVector().subtract(from.toVector()).normalize();
        World world = from.getWorld();
        for (int i = 0; i < 4; i++) {
            Location start = from.clone().add(dir.clone().multiply(0.8 + i * 0.45));
            world.spawnParticle(Particle.TRAIL, start, 1, 0, 0, 0, 0, new Particle.Trail(to, TRACER, ticks + i % 2), true);
        }
    }

    // ------------------------------------------------------------------ upkeep

    private void tick() {
        long now = Bukkit.getCurrentTick();
        if (!pulses.isEmpty()) {
            Iterator<Pulse> it = pulses.iterator();
            while (it.hasNext()) {
                Pulse pulse = it.next();
                if (pulse.until() > now) {
                    continue;
                }
                it.remove();
                Block block = pulse.world().getBlockAt(pulse.x(), pulse.y(), pulse.z());
                for (UUID id : pulse.viewers()) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null && player.getWorld() == pulse.world()) {
                        player.sendBlockChange(block.getLocation(), block.getBlockData());
                    }
                }
            }
        }
        if (now % 20 == 0) {
            for (Iterator<Map.Entry<UUID, Deque<Decal>>> worlds = decals.entrySet().iterator(); worlds.hasNext(); ) {
                Deque<Decal> list = worlds.next().getValue();
                while (!list.isEmpty() && (list.peekFirst().expires() <= now || !list.peekFirst().entity().isValid())) {
                    list.removeFirst().entity().remove();
                }
                if (list.isEmpty()) {
                    worlds.remove();
                }
            }
            if (rounds.size() > 256) {
                rounds.clear();
            }
        }
    }
}
