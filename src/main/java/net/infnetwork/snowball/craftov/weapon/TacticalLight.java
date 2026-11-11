package net.infnetwork.snowball.craftov.weapon;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Nms;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gun-mounted tactical light, cycled with the middle mouse button: off, steady, strobe. Vanilla has no
 * moving lights, so the beam is a client-side light block where it lands (plus a dim one at the
 * muzzle), moved every tick and shown to everyone nearby. The strobe blinks at about 5 Hz; anyone
 * caught looking into it gets a white flicker on screen and soldiers lose their aim for a moment.
 */
public final class TacticalLight implements Listener {

    public enum Mode { OFF, STEADY, STROBE }

    private static final double RANGE = 40;
    private static final double BLIND_RANGE = 22;
    private static final double BLIND_COS = Math.cos(Math.toRadians(14));

    /** A client-side light block and who was sent it. */
    private record Lit(World world, int x, int y, int z, List<UUID> viewers) {
        boolean at(Block block) {
            return block.getWorld() == world && block.getX() == x && block.getY() == y && block.getZ() == z;
        }
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, Mode> modes = new HashMap<>();
    private final Map<UUID, Lit> spots = new HashMap<>();
    private final Map<UUID, Lit> glows = new HashMap<>();
    /** What makes the light visible even in daylight: a glowing spot where it lands and a lens flare. */
    private final Map<UUID, org.bukkit.entity.ItemDisplay> spotFx = new HashMap<>();
    private final Map<UUID, org.bukkit.entity.ItemDisplay> flareFx = new HashMap<>();

    public TacticalLight(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Nms.onPick(plugin, this::cycle);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    public Mode mode(Player player) {
        return modes.getOrDefault(player.getUniqueId(), Mode.OFF);
    }

    /** Middle click: off -> steady -> strobe (if the light has one) -> off. */
    private void cycle(Player player) {
        WeaponService.GunState state = plugin.weapons().state(player);
        if (state == null || state.light() == null) {
            player.sendActionBar(Text.mm("<#9AA0A6>这把枪没有装战术手电 <#6B7079>(改枪台 · 战术位)"));
            return;
        }
        Mode next = switch (mode(player)) {
            case OFF -> Mode.STEADY;
            case STEADY -> "strobe".equals(state.light()) ? Mode.STROBE : Mode.OFF;
            case STROBE -> Mode.OFF;
        };
        modes.put(player.getUniqueId(), next);
        if (next == Mode.OFF) {
            clear(player.getUniqueId());
        }
        player.playSound(player.getLocation(), "craftov:gun.mode", SoundCategory.PLAYERS, 0.7f, next == Mode.OFF ? 0.8f : 1.3f);
        player.sendActionBar(Text.mm("<#F2E6C8>战术手电 " + switch (next) {
            case OFF -> "<#9AA0A6>关";
            case STEADY -> "<#F2C94C>常亮";
            case STROBE -> "<#FF8A80>爆闪";
        }));
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        for (Map.Entry<UUID, Mode> entry : new ArrayList<>(modes.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Mode mode = entry.getValue();
            WeaponService.GunState state = player == null ? null : plugin.weapons().state(player);
            if (player == null || mode == Mode.OFF || state == null || state.light() == null || player.isDead()
                    || player.getGameMode() == GameMode.SPECTATOR) {
                clear(entry.getKey());
                if (player == null) {
                    modes.remove(entry.getKey());
                }
                continue;
            }
            boolean on = mode == Mode.STEADY || (now / 2) % 2 == 0;
            Location eye = player.getEyeLocation();
            Vector dir = eye.getDirection();
            showFx(player, eye, dir, on, mode == Mode.STROBE);
            Block spot = on ? spotBlock(eye, dir) : null;
            Block glow = on ? airAt(eye.clone().add(dir.clone().multiply(1.2))) : null;
            move(spots, player.getUniqueId(), spot, 15);
            move(glows, player.getUniqueId(), glow, 9);
            if (mode == Mode.STROBE && now % 4 == 0) {
                dazzle(player, eye, dir);
            }
        }
    }

    /** The air block just in front of where the beam lands. */
    private static @Nullable Block spotBlock(Location eye, Vector dir) {
        RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, dir, RANGE, FluidCollisionMode.NEVER, true);
        if (hit == null) {
            return null;
        }
        Vector at = hit.getHitPosition();
        for (double back : new double[]{0.45, 1.1, 1.8}) {
            Block block = airAt(at.clone().subtract(dir.clone().multiply(back)).toLocation(eye.getWorld()));
            if (block != null) {
                return block;
            }
        }
        return null;
    }

    private static @Nullable Block airAt(Location at) {
        Block block = at.getBlock();
        return block.getType().isAir() ? block : null;
    }

    private void move(Map<UUID, Lit> lights, UUID owner, @Nullable Block target, int level) {
        Lit current = lights.get(owner);
        if (current != null && target != null && current.at(target)) {
            return;
        }
        if (current != null) {
            revert(current);
            lights.remove(owner);
        }
        if (target == null) {
            return;
        }
        Light light = (Light) Material.LIGHT.createBlockData();
        light.setLevel(level);
        List<UUID> viewers = new ArrayList<>();
        Location where = target.getLocation();
        for (Player viewer : target.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(where) < 64 * 64) {
                viewer.sendBlockChange(where, light);
                viewers.add(viewer.getUniqueId());
            }
        }
        lights.put(owner, new Lit(target.getWorld(), target.getX(), target.getY(), target.getZ(), viewers));
    }

    private static void revert(Lit lit) {
        Block block = lit.world().getBlockAt(lit.x(), lit.y(), lit.z());
        for (UUID id : lit.viewers()) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null && viewer.getWorld() == lit.world()) {
                viewer.sendBlockChange(block.getLocation(), block.getBlockData());
            }
        }
    }

    /** The visible part: a full-bright spot on the lit surface and a flare at the lens for everyone else. */
    private void showFx(Player player, Location eye, Vector dir, boolean on, boolean strobe) {
        UUID id = player.getUniqueId();
        RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, dir, RANGE, FluidCollisionMode.NEVER, true);
        org.bukkit.entity.ItemDisplay spot = spotFx.get(id);
        if (hit != null && hit.getHitBlockFace() != null) {
            Vector normal = hit.getHitBlockFace().getDirection();
            double distance = hit.getHitPosition().distance(eye.toVector());
            float size = on ? (float) (0.7 + distance * 0.09) * (strobe ? 1.25f : 1f) : 0f;
            Location at = hit.getHitPosition().toLocation(eye.getWorld()).add(normal.clone().multiply(0.02));
            at.setYaw(0);
            at.setPitch(0);
            org.joml.Quaternionf facing = new org.joml.Quaternionf().rotationTo(0, 0, 1,
                    (float) normal.getX(), (float) normal.getY(), (float) normal.getZ());
            if (spot == null || !spot.isValid()) {
                spot = fxDisplay(at, "fx/light_spot", org.bukkit.entity.Display.Billboard.FIXED, null);
                spotFx.put(id, spot);
            } else {
                spot.teleport(at);
            }
            spot.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), facing,
                    new org.joml.Vector3f(size, size, size), new org.joml.Quaternionf()));
        } else if (spot != null) {
            spot.remove();
            spotFx.remove(id);
        }
        Location lens = Ballistics.muzzlePoint(player, dir).add(dir.clone().multiply(0.2));
        org.bukkit.entity.ItemDisplay flare = flareFx.get(id);
        if (flare == null || !flare.isValid()) {
            // The holder sees the beam, not their own lens: hidden before it is first sent.
            flare = fxDisplay(lens, "fx/light_flare", org.bukkit.entity.Display.Billboard.CENTER, player);
            flareFx.put(id, flare);
        } else {
            flare.teleport(lens);
        }
        float flareSize = on ? (strobe ? 1.1f : 0.6f) : 0f;
        flare.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                new org.joml.Vector3f(flareSize, flareSize, flareSize), new org.joml.Quaternionf()));
    }

    private org.bukkit.entity.ItemDisplay fxDisplay(Location at, String model, org.bukkit.entity.Display.Billboard billboard,
                                                    @Nullable Player hiddenFrom) {
        org.bukkit.inventory.ItemStack item = org.bukkit.inventory.ItemStack.of(Material.PAPER);
        item.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key("craftov", model));
        return at.getWorld().spawn(at, org.bukkit.entity.ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(billboard);
            d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            d.setPersistent(false);
            d.setTeleportDuration(1);
            d.setViewRange(1.5f);
            if (hiddenFrom != null) {
                hiddenFrom.hideEntity(plugin, d);
            }
        });
    }

    private void clear(UUID owner) {
        org.bukkit.entity.ItemDisplay spotDisplay = spotFx.remove(owner);
        if (spotDisplay != null) {
            spotDisplay.remove();
        }
        org.bukkit.entity.ItemDisplay flare = flareFx.remove(owner);
        if (flare != null) {
            flare.remove();
        }
        Lit spot = spots.remove(owner);
        if (spot != null) {
            revert(spot);
        }
        Lit glow = glows.remove(owner);
        if (glow != null) {
            revert(glow);
        }
    }

    /** Whoever is inside the beam and looking into it is blinded for a moment. */
    private void dazzle(Player holder, Location eye, Vector dir) {
        World world = eye.getWorld();
        for (LivingEntity target : world.getNearbyLivingEntities(eye, BLIND_RANGE)) {
            if (target == holder || !(target instanceof Player || plugin.ai().isAi(target))) {
                continue;
            }
            Location theirEye = target.getEyeLocation();
            Vector to = theirEye.toVector().subtract(eye.toVector());
            double distance = to.length();
            if (distance < 0.5 || to.clone().normalize().dot(dir) < BLIND_COS) {
                continue;
            }
            Vector back = to.clone().multiply(-1 / distance);
            if (theirEye.getDirection().dot(back) < 0.55) {
                continue; // facing away from the light
            }
            RayTraceResult wall = world.rayTraceBlocks(eye, to.clone().normalize(), distance - 0.3, FluidCollisionMode.NEVER, true);
            if (wall != null && wall.getHitBlock() != null) {
                continue;
            }
            if (target instanceof Player player) {
                if (player.getGameMode() != GameMode.SPECTATOR) {
                    plugin.hud().dazzle(player, 12);
                }
            } else {
                plugin.ai().stun(target.getLocation(), 0.8, 24);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
        modes.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        clear(event.getPlayer().getUniqueId());
        modes.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        // Switching away from the gun puts the light out; it stays off until clicked on again.
        clear(event.getPlayer().getUniqueId());
        modes.remove(event.getPlayer().getUniqueId());
    }
}
