package net.infnetwork.snowball.craftov.combat;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
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
 * footsteps.stride blocks covered (ground crossed in a jump counts, and the step it is owed falls on
 * the landing, as vanilla's does), heard out to footsteps.walk-range blocks walking and
 * footsteps.sprint-range sprinting, and a landing from footsteps.land-min-fall blocks or more out to
 * footsteps.land-range. A wall between cuts the range to footsteps.wall-share of it, muffled by
 * distance like the rest. Sneaking is the silent walk: no steps at all (footsteps.sneak-range 0),
 * and in a raid it is faster than vanilla's (footsteps.sneak-speed, the sneaking-speed attribute,
 * 0.45 of the walk where vanilla's is 0.3), so it is a way to move, not only to stand still. A slide
 * (SlideService) is not: it scrapes along the ground ({@code craftov:move.slide} as it starts), heard
 * out to footsteps.slide-range, sneak key or not. The soldiers hear by the same ranges and walls
 * ({@link #heardAt}), whether footsteps.enabled is on or not. The player's own client still plays
 * their own vanilla steps for them.
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
        /** How far the feet last rose (or, below 0, fell) in a tick they moved up or down at all. */
        double rise;
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

    /**
     * Follows one player's feet: a step every stride, a landing after a fall. Ground covered in the
     * air counts towards the next step as it does in vanilla, and the step falls due when the feet
     * come down: a jump on the run lands with a step (the run sound on a sprint), so sprint-jumping
     * across a yard is heard about once a jump rather than hardly at all.
     */
    private void step(Player player) {
        Location at = player.getLocation();
        Walk walk = walks.computeIfAbsent(player.getUniqueId(), k -> new Walk());
        double dx = at.getX() - walk.x;
        double dz = at.getZ() - walk.z;
        double moved = Math.sqrt(dx * dx + dz * dz);
        double rise = at.getY() - walk.y;
        boolean fresh = walk.world != at.getWorld() || moved > 4 || Math.abs(rise) > 4;
        walk.world = at.getWorld();
        walk.x = at.getX();
        walk.y = at.getY();
        walk.z = at.getZ();
        // Coming down and then going up, the feet touched the ground in between: the server sees the
        // player once a tick, and a landing and the next jump can arrive in one (after a tick that
        // brought no move at all, which is why a tick without a rise or fall is passed over).
        boolean bounced = !fresh && !walk.onGround && walk.rise < -1e-3 && rise > 1e-3;
        if (fresh || Math.abs(rise) > 1e-3) {
            walk.rise = fresh ? 0 : rise;
        }
        boolean ground = grounded(player);
        if (fresh || silent(player)) {
            walk.walked = 0;
            walk.onGround = ground;
            walk.top = at.getY();
            return;
        }
        walk.walked += moved;
        double stride = Math.max(0.5, plugin.getConfig().getDouble("footsteps.stride", 1.6));
        if (!ground && !bounced) {
            walk.top = walk.onGround ? at.getY() : Math.max(walk.top, at.getY());
            walk.onGround = false;
            return;
        }
        if (!walk.onGround) {
            // Touchdown: from a fall a landing, from a jump or a step down the step it was owed.
            double fell = walk.top - (bounced ? at.getY() - rise : at.getY());
            walk.onGround = !bounced;
            walk.top = at.getY();
            if (fell >= plugin.getConfig().getDouble("footsteps.land-min-fall", 1.5)) {
                walk.walked = 0;
                emit(player, "land", plugin.getConfig().getDouble("footsteps.land-range", 24));
                return;
            }
            if (walk.walked >= stride) {
                walk.walked = 0;
                emit(player, player.isSprinting() ? "run" : "walk", range(player));
            }
            return;
        }
        if (walk.walked >= stride) {
            walk.walked %= stride;
            emit(player, player.isSprinting() ? "run" : "walk", range(player));
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
     * How far this player's footsteps carry right now in the open (blocks): sneaking, walking or
     * sprinting, by footsteps.*-range; 0 while they make no steps (downed, swimming, riding...).
     */
    private double range(Player player) {
        var config = plugin.getConfig();
        if (plugin.slides().isSliding(player)) {
            return Math.max(0, config.getDouble("footsteps.slide-range", 24));
        }
        if (silent(player)) {
            return 0;
        }
        if (player.isSneaking()) {
            return Math.max(0, config.getDouble("footsteps.sneak-range", 0));
        }
        return player.isSprinting() ? config.getDouble("footsteps.sprint-range", 32) : config.getDouble("footsteps.walk-range", 16);
    }

    /**
     * Whether a moving player's footsteps reach {@code ear} (a soldier's): within the range they carry
     * in the open, or footsteps.wall-share of it with a solid block in between, as a player standing
     * there would hear them. footsteps.enabled only chooses whether players hear these steps or
     * vanilla's; soldiers hear by these ranges either way.
     */
    public boolean heardAt(Player player, Location ear) {
        double range = range(player);
        Location feet = plugin.slides().feet(player).add(0, 0.1, 0);
        if (range <= 0 || ear.getWorld() != feet.getWorld()) {
            return false;
        }
        double d = ear.distance(feet);
        if (d >= range) {
            return false;
        }
        double wallShare = Math.max(0, Math.min(1, plugin.getConfig().getDouble("footsteps.wall-share", 0.5)));
        return d < range * wallShare || !occluded(ear, feet);
    }

    /**
     * Plays one footstep of {@code player} to everyone else in earshot: louder the nearer, out to
     * {@code range} blocks in the open and footsteps.wall-share of it with a wall in between, from
     * where the feet are, so it comes from the right direction.
     */
    private void emit(Player player, String kind, double range) {
        double loudness = kind.equals("walk") ? 0.55 : kind.equals("run") ? 0.75 : 0.9;
        float pitch = (float) ((kind.equals("walk") ? 1.0 : 0.92) + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.12);
        play(player, player.getLocation().add(0, 0.1, 0), "craftov:step." + surface(player) + "." + kind, range, loudness, pitch);
    }

    /**
     * The scrape of a slide setting off, from the ground under the sliding player (SlideService): to
     * everyone in footsteps.slide-range as a footstep is heard, and to the player too.
     */
    public void slide(Player player) {
        Location ground = plugin.slides().feet(player).add(0, 0.1, 0);
        float pitch = (float) (1.0 + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.1);
        play(player, ground, "craftov:move.slide", Math.max(0, plugin.getConfig().getDouble("footsteps.slide-range", 24)), 0.85, pitch);
        player.playSound(ground, "craftov:move.slide", SoundCategory.PLAYERS, 0.6f, pitch);
    }

    /** Plays {@code key} from {@code feet} to everyone but {@code player} in earshot (see {@link #emit}). */
    private void play(Player player, Location feet, String key, double range, double loudness, float pitch) {
        if (range <= 0) {
            return;
        }
        double wallShare = Math.max(0, Math.min(1, plugin.getConfig().getDouble("footsteps.wall-share", 0.5)));
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
