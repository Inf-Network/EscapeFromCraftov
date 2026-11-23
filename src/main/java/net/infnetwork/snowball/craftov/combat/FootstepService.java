package net.infnetwork.snowball.craftov.combat;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Footsteps that matter. Vanilla plays other players' steps at 0.15 of a block's step sound, which
 * carries a few blocks at best, and a sneaking player makes none, so neither walking nor the silent
 * walk told anyone anything. Here every player's vanilla step and landing sounds are kept from
 * everyone else (the view handler drops them, see {@link #replaces}) and the server plays its own:
 * {@code craftov:step.<surface>.<walk|run|land>} by the ground underfoot, one every
 * footsteps.stride blocks walked on the ground, heard out to footsteps.walk-range blocks walking and
 * footsteps.sprint-range sprinting, and a landing from footsteps.land-min-fall blocks or more out to
 * footsteps.land-range. A wall between cuts the range to footsteps.wall-share of it, muffled by
 * distance like the rest. Sneaking is the silent walk: no steps at all (footsteps.sneak-range 0),
 * and in a raid it is faster than vanilla's (footsteps.sneak-speed, the sneaking-speed attribute,
 * 0.45 of the walk where vanilla's is 0.3), so it is a way to move, not only to stand still. The
 * soldiers hear by the same ranges ({@link #range}). The player's own client still plays their own
 * vanilla steps for them.
 */
public final class FootstepService implements Listener {

    private static final NamespacedKey SILENT_WALK = new NamespacedKey("craftov", "silent_walk");
    /** Vanilla's sneaking speed, the attribute's base. */
    private static final double VANILLA_SNEAK = 0.3;

    /** One player's walk since the last step. */
    private static final class Walk {
        @Nullable World world;
        double x;
        double y;
        double z;
        double walked;
        boolean onGround = true;
        /** Highest point since the feet last touched the ground. */
        double top;
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, Walk> walks = new HashMap<>();
    /** Read by the Netty threads (see {@link #replaces}). */
    private volatile boolean enabled;

    public FootstepService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private boolean enabled() {
        enabled = plugin.getConfig().getBoolean("footsteps.enabled", true);
        return enabled;
    }

    private void tick() {
        boolean on = enabled();
        for (Player player : Bukkit.getOnlinePlayers()) {
            silentWalk(player, on);
            if (!on) {
                walks.remove(player.getUniqueId());
                continue;
            }
            step(player);
        }
    }

    /** Follows one player's feet: a step every stride on the ground, a landing after a fall. */
    private void step(Player player) {
        Location at = player.getLocation();
        Walk walk = walks.computeIfAbsent(player.getUniqueId(), k -> new Walk());
        double dx = at.getX() - walk.x;
        double dz = at.getZ() - walk.z;
        double moved = Math.sqrt(dx * dx + dz * dz);
        boolean fresh = walk.world != at.getWorld() || moved > 4 || Math.abs(at.getY() - walk.y) > 4;
        walk.world = at.getWorld();
        walk.x = at.getX();
        walk.y = at.getY();
        walk.z = at.getZ();
        boolean ground = grounded(player);
        if (fresh || silent(player)) {
            walk.walked = 0;
            walk.onGround = ground;
            walk.top = at.getY();
            return;
        }
        if (!ground) {
            walk.top = walk.onGround ? at.getY() : Math.max(walk.top, at.getY());
            walk.onGround = false;
            return;
        }
        if (!walk.onGround) {
            walk.onGround = true;
            double fell = walk.top - at.getY();
            if (fell >= plugin.getConfig().getDouble("footsteps.land-min-fall", 1.5)) {
                walk.walked = 0;
                emit(player, "land", plugin.getConfig().getDouble("footsteps.land-range", 24));
                return;
            }
        }
        walk.walked += moved;
        double stride = Math.max(0.5, plugin.getConfig().getDouble("footsteps.stride", 1.6));
        if (walk.walked >= stride) {
            walk.walked %= stride;
            boolean running = player.isSprinting();
            emit(player, running ? "run" : "walk", range(player));
        }
    }

    /**
     * Something solid under the feet, read from the world rather than the client's on-ground flag:
     * any corner of the feet just above a block that cannot be walked through.
     */
    private static boolean grounded(Player player) {
        var box = player.getBoundingBox();
        World world = player.getWorld();
        double y = box.getMinY() - 0.06;
        for (double x : new double[]{box.getMinX() + 0.01, box.getMaxX() - 0.01}) {
            for (double z : new double[]{box.getMinZ() + 0.01, box.getMaxZ() - 0.01}) {
                Block block = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                if (!block.isPassable()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Spectators, the downed (crawling is dragging, not stepping), riders, swimmers and fliers make no steps. */
    private boolean silent(Player player) {
        return player.getGameMode() == GameMode.SPECTATOR || player.isInsideVehicle() || player.isSwimming()
                || player.isGliding() || player.isFlying() || player.isInWater() || plugin.downed().isDowned(player);
    }

    /**
     * How far this player's footsteps carry right now (blocks): sneaking, walking or sprinting, by
     * footsteps.*-range. Soldiers hear by it too. 0 while they stand still or make no steps.
     */
    public double range(Player player) {
        var config = plugin.getConfig();
        if (!config.getBoolean("footsteps.enabled", true) || silent(player)) {
            return 0;
        }
        if (player.isSneaking()) {
            return Math.max(0, config.getDouble("footsteps.sneak-range", 0));
        }
        return player.isSprinting() ? config.getDouble("footsteps.sprint-range", 32) : config.getDouble("footsteps.walk-range", 16);
    }

    /**
     * Plays one footstep of {@code player} to everyone else in earshot: louder the nearer, out to
     * {@code range} blocks in the open and footsteps.wall-share of it with a wall in between, from
     * where the feet are, so it comes from the right direction.
     */
    private void emit(Player player, String kind, double range) {
        if (range <= 0) {
            return;
        }
        Location feet = player.getLocation().add(0, 0.1, 0);
        String key = "craftov:step." + surface(player) + "." + kind;
        double wallShare = Math.max(0, Math.min(1, plugin.getConfig().getDouble("footsteps.wall-share", 0.5)));
        double loudness = kind.equals("walk") ? 0.55 : kind.equals("run") ? 0.75 : 0.9;
        float pitch = (float) ((kind.equals("walk") ? 1.0 : 0.92) + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.12);
        for (Player listener : feet.getWorld().getPlayers()) {
            if (listener == player || listener.getGameMode() == GameMode.SPECTATOR && listener.getSpectatorTarget() == player) {
                continue;
            }
            Location ear = listener.getEyeLocation();
            double d = ear.distance(feet);
            if (d > range) {
                continue;
            }
            double reach = occluded(ear, feet) ? range * wallShare : range;
            if (d > reach) {
                continue;
            }
            double gain = loudness * Math.pow(1 - d / reach, 1.4);
            if (gain > 0.01) {
                plugin.weapons().audio().heard(listener, feet, key, gain, pitch);
            }
        }
    }

    /** A solid block between the ear and the feet (doors and glass let steps through). */
    private static boolean occluded(Location ear, Location feet) {
        Vector line = feet.toVector().subtract(ear.toVector());
        double length = line.length();
        if (length < 1) {
            return false;
        }
        RayTraceResult hit = ear.getWorld().rayTraceBlocks(ear, line.multiply(1 / length), length - 0.5, FluidCollisionMode.NEVER, true);
        return hit != null && hit.getHitBlock() != null && hit.getHitBlock().getType().isOccluding();
    }

    /** What the feet are on: the block below, or a carpet or snow layer they stand in. */
    private static String surface(Player player) {
        Location feet = player.getLocation();
        Block in = feet.getBlock();
        Material type = Tag.WOOL_CARPETS.isTagged(in.getType()) || in.getType() == Material.SNOW ? in.getType()
                : feet.clone().subtract(0, 0.2, 0).getBlock().getType();
        return surface(type);
    }

    /** The footstep family of a block: stone, wood, metal, gravel, grass, sand, snow or cloth. */
    public static String surface(Material type) {
        String name = type.name();
        if (Tag.WOOL.isTagged(type) || Tag.WOOL_CARPETS.isTagged(type)) {
            return "cloth";
        }
        if (name.contains("SNOW")) {
            return "snow";
        }
        if (Tag.SAND.isTagged(type)) {
            return "sand";
        }
        if (name.contains("GRAVEL")) {
            return "gravel";
        }
        if (Tag.DIRT.isTagged(type) || Tag.LEAVES.isTagged(type) || name.contains("GRASS") || name.contains("MUD")
                || name.contains("MOSS") || name.contains("HAY") || name.contains("FARMLAND") || name.contains("PATH")) {
            return "grass";
        }
        if (Tag.PLANKS.isTagged(type) || Tag.LOGS.isTagged(type) || Tag.WOODEN_SLABS.isTagged(type) || Tag.WOODEN_STAIRS.isTagged(type)
                || Tag.WOODEN_TRAPDOORS.isTagged(type) || Tag.WOODEN_DOORS.isTagged(type) || name.contains("BARREL")
                || name.contains("CHEST") || name.contains("SCAFFOLD") || name.contains("BOOKSHELF") || name.contains("CRAFTING")) {
            return "wood";
        }
        if (name.contains("IRON") || name.contains("COPPER") || name.contains("CHAIN") || name.contains("ANVIL") || name.contains("HOPPER")
                || name.contains("CAULDRON") || name.contains("GRATE") || name.contains("NETHERITE") || name.contains("GOLD_BLOCK")
                || name.contains("HEAVY_CORE")) {
            return "metal";
        }
        return "stone";
    }

    /**
     * True for a sound the view handler keeps from listeners (on a Netty thread): another player's
     * vanilla step or landing on a block, which these footsteps replace (also under the name
     * CraftEngine gives block sounds it takes over, minecraft:replaced.block.*).
     */
    public boolean replaces(String key, String source) {
        return enabled && "PLAYERS".equals(source) && (key.startsWith("minecraft:block.") || key.startsWith("minecraft:replaced.block."))
                && (key.endsWith(".step") || key.endsWith(".fall"));
    }

    /**
     * In a raid the silent walk is quicker (footsteps.sneak-speed of the walk, vanilla 0.3): a modifier
     * on the sneaking-speed attribute, taken off outside a raid. Not while downed: the client scales
     * the crawl by the same attribute, and the crawl's pace is HealthService's (downed movement 0.35
     * of the walk, crawled at vanilla's 0.3 of that).
     */
    private void silentWalk(Player player, boolean on) {
        AttributeInstance attribute = player.getAttribute(Attribute.SNEAKING_SPEED);
        if (attribute == null) {
            return;
        }
        double want = on && plugin.raids().raidOf(player) != null && !plugin.downed().isDowned(player)
                ? Math.max(0, Math.min(1, plugin.getConfig().getDouble("footsteps.sneak-speed", 0.45))) - VANILLA_SNEAK : 0;
        AttributeModifier current = attribute.getModifier(SILENT_WALK);
        if (current != null && Math.abs(current.getAmount() - want) < 1e-6) {
            return;
        }
        if (current != null) {
            attribute.removeModifier(SILENT_WALK);
        }
        if (Math.abs(want) > 1e-6) {
            attribute.addTransientModifier(new AttributeModifier(SILENT_WALK, want, AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        walks.remove(event.getPlayer().getUniqueId());
        AttributeInstance attribute = event.getPlayer().getAttribute(Attribute.SNEAKING_SPEED);
        if (attribute != null && attribute.getModifier(SILENT_WALK) != null) {
            attribute.removeModifier(SILENT_WALK);
        }
    }
}
