package net.infnetwork.snowball.craftov.weapon;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.DamageService;
import net.infnetwork.snowball.craftov.combat.HitZone;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.util.Notify;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Grenades. Holding right mouse pulls the pin and starts the fuse (cooking), and the action bar
 * counts it down; letting go throws the grenade with what is left of it, so a grenade held a while
 * goes off sooner after it lands, or in the air. Held to the end of its fuse it goes off in the
 * hand. Taking out another item while the pin is out puts it back. One throw locks every grenade
 * (only grenades: they have a cooldown group of their own) for a second.
 *
 * <p>A thrown grenade flies under its own physics: gravity and air drag as for a dropped item (so
 * throws reach as far as they always did), but it bounces off walls and ceilings with
 * {@code throwables.bounce.wall} of its speed into them and off the floor with
 * {@code throwables.bounce.floor}, then rolls to a stop, and goes off where it lies when its fuse
 * runs out.
 */
public final class ThrowableService implements Listener {

    /** Gravity and air drag per tick, as vanilla gives a dropped item. */
    private static final double GRAVITY = 0.04;
    private static final double DRAG = 0.98;
    /** Horizontal speed kept per tick while rolling on the ground. */
    private static final double ROLL = 0.7;
    /** Speed kept along a wall or ceiling in an impact, and along the floor in a landing. */
    private static final double WALL_SCRAPE = 0.85;
    private static final double FLOOR_SCRAPE = 0.6;
    /** A floor bounce slower than this settles the grenade instead. */
    private static final double SETTLE = 0.08;
    /** How close the grenade's centre comes to a face: half its size. */
    private static final double RADIUS = 0.125;
    /** The clink of an impact needs at least this speed (blocks per tick). */
    private static final double CLINK = 0.15;
    /** Ticks every grenade is locked for after a throw. */
    private static final int THROW_COOLDOWN = 20;
    /** The axes in the order a grenade moves along them: up and down first, as vanilla moves entities. */
    private static final int[] AXES = {1, 0, 2};
    /** Ticks after the button went down for the client to take hold of the grenade. */
    private static final int HOLD_GRACE = 2;
    /** Notify key of the fuse countdown, so each update replaces the last. */
    private static final String COOK_LINE = "grenade-cook";
    /** The longest flight the preview follows, in ticks (longer than any fuse in items.yml). */
    private static final int PREVIEW_TICKS = 100;
    /** Preview dots: small and pale along the path, bigger and red where the grenade goes off. */
    private static final Particle.DustOptions PREVIEW_ARC = new Particle.DustOptions(org.bukkit.Color.fromRGB(0xF2F2F2), 0.8f);
    private static final Particle.DustOptions PREVIEW_END = new Particle.DustOptions(org.bukkit.Color.fromRGB(0xFF4D4D), 1.1f);

    /** Share of the speed into a face a grenade keeps off it: walls and ceilings, and the floor. */
    private record Bounce(double wall, double floor) {
    }

    /**
     * A grenade whose pin came out on tick {@code start}, when the button went down: it goes off
     * a fuse later, whether or not it has left the hand by then. {@code held} once the client has
     * taken hold of it (its item use began), which is when the pin is heard.
     */
    private static final class Cook {
        private final ItemDef def;
        private final long start;
        private boolean held;

        private Cook(ItemDef def, long start) {
            this.def = def;
            this.start = start;
        }

        private long goesOff() {
            return start + def.throwable().fuseTicks();
        }
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, Cook> cooks = new HashMap<>();
    /** Player -> tick a click on an entity of theirs was taken (a box or body opened, a revive). */
    private final Map<UUID, Long> entityClicks = new HashMap<>();

    public ThrowableService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickCooks, 1L, 1L);
    }

    private boolean isGrenade(@Nullable ItemStack stack, ItemDef def) {
        ItemDef held = plugin.items().defOf(stack);
        return held != null && held.id().equals(def.id());
    }

    /**
     * Right mouse going down with a grenade in hand pulls the pin: the fuse starts now, and the
     * client goes on holding the grenade (vanilla's item use, see ItemFactory) until the button comes
     * up ({@link #onRelease}). Doors, levers and buttons within reach still work with a grenade in
     * hand, and so does a click on a box, a body or a downed squadmate: the client sends the use
     * straight after one, which must not pull the pin.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPress(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()) {
            return;
        }
        ItemDef def = plugin.items().defOf(event.getItem());
        if (def == null || def.throwable() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND
                || Bukkit.getCurrentTick() - entityClicks.getOrDefault(player.getUniqueId(), -100L) <= 1) {
            event.setUseItemInHand(Event.Result.DENY); // thrown from the main hand only, never off a click on an entity
            return;
        }
        Block clicked = event.getClickedBlock();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && clicked != null && clicked.getType().isInteractable()
                && !player.isSneaking() && clicked.getLocation().add(0.5, 0.5, 0.5).distanceSquared(player.getEyeLocation()) < 16) {
            return; // the client uses the door instead of the grenade
        }
        if (!plugin.raids().canAttack(player) || plugin.lobby().isLobby(player.getWorld())
                || player.getCooldown(ItemFactory.THROW_GROUP) > 0 || event.useItemInHand() == Event.Result.DENY) {
            event.setCancelled(true);
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.ALLOW);
        // Clicking a block, the client sends the use straight after this; the cook only counts once
        // it is holding the grenade (tickCooks).
        if (!cooks.containsKey(player.getUniqueId())) {
            plugin.raids().onAttack(player);
            cooks.put(player.getUniqueId(), new Cook(def, Bukkit.getCurrentTick()));
        }
    }

    /** Right mouse coming up throws the cooked grenade, with what is left of its fuse. */
    @EventHandler
    public void onRelease(PlayerStopUsingItemEvent event) {
        Player player = event.getPlayer();
        Cook cook = cooks.remove(player.getUniqueId());
        if (cook == null) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isGrenade(hand, cook.def) || !plugin.raids().canFire(player)) {
            return; // the pin goes back in
        }
        if (!cook.held) {
            pinOut(player); // a tap: the pin comes out as it leaves the hand
        }
        if (Bukkit.getCurrentTick() >= cook.goesOff()) {
            goesOffInHand(player, cook);
            return;
        }
        launch(player, takeOne(player, hand, cook.def), cook.def, cook.goesOff());
        Notify.critical(player, COOK_LINE, "<muted>已投出");
    }

    /**
     * Follows every grenade with its pin out. The client holding it confirms the pin is out (and
     * it is heard); once it stops holding it without letting go (another item, a menu taking the
     * hand, downed, reviving, gone) the pin goes back in. Held to the end of its fuse it goes off
     * in the hand. Meanwhile the action bar counts the fuse down.
     */
    private void tickCooks() {
        if (cooks.isEmpty()) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        for (UUID id : new ArrayList<>(cooks.keySet())) {
            Cook cook = cooks.get(id);
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                cooks.remove(id);
                continue;
            }
            boolean holding = player.hasActiveItem() && isGrenade(player.getActiveItem(), cook.def);
            // A menu over the screen takes the hand: the client would only let go of the grenade
            // once it closes, long after it went off in the hand.
            boolean menu = holding && menuOpen(player);
            if (!cook.held) {
                if (!holding || menu) {
                    if (menu || now - cook.start > HOLD_GRACE) {
                        cooks.remove(id); // the client never took hold of it: nothing happened
                        if (menu) {
                            player.clearActiveItem();
                        }
                    }
                    continue;
                }
                cook.held = true;
                pinOut(player);
            }
            boolean able = plugin.raids().canFire(player);
            if (!holding || !able || menu) {
                cooks.remove(id);
                if (holding) {
                    player.clearActiveItem(); // letting go later throws nothing
                }
                if (able) {
                    Notify.critical(player, COOK_LINE, "<muted>保险销已插回");
                }
                continue;
            }
            if (now >= cook.goesOff()) {
                cooks.remove(id);
                goesOffInHand(player, cook);
                continue;
            }
            if ((now - cook.start) % 2 == 0) {
                Notify.critical(player, COOK_LINE, "<accent>已拉环 <text>" + String.format(java.util.Locale.ROOT, "%.1f", (cook.goesOff() - now) / 20.0)
                        + " 秒 <muted>· 松开右键投掷");
                if (plugin.getConfig().getBoolean("throwables.preview", true)) {
                    preview(player, cook, now);
                }
            }
        }
    }

    /**
     * Dots only the thrower sees along the path the grenade would take if let go now, bounces and
     * all, and a red one where it would go off: where it comes to rest, or wherever its fuse runs
     * out first. The client cannot draw an arc of its own, so particles stand in for one; the first
     * blocks are left out, where they would only get in the way of the view.
     */
    private void preview(Player player, Cook cook, long now) {
        World world = player.getWorld();
        double[] pos = throwOrigin(player);
        double[] vel = throwVelocity(player);
        Bounce bounce = bounce();
        long ticks = Math.min(cook.goesOff() - now, PREVIEW_TICKS);
        for (int t = 1; t <= ticks; t++) {
            step(world, pos, vel, bounce);
            if (vel[0] == 0 && vel[1] == 0 && vel[2] == 0) {
                break; // at rest
            }
            if (t > 2) {
                player.spawnParticle(Particle.DUST, pos[0], pos[1], pos[2], 1, 0, 0, 0, 0, PREVIEW_ARC, true);
            }
        }
        player.spawnParticle(Particle.DUST, pos[0], pos[1], pos[2], 1, 0, 0, 0, 0, PREVIEW_END, true);
    }

    /** Whether a menu of the server's is open over the screen (the player's own inventory is not one it knows of). */
    private static boolean menuOpen(Player player) {
        InventoryType type = player.getOpenInventory().getType();
        return type != InventoryType.CRAFTING && type != InventoryType.CREATIVE;
    }

    /** A click on an entity that something took (a box or body opened, a revive begun): no pin follows it. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityClick(PlayerInteractEntityEvent event) {
        if (event.isCancelled()) {
            entityClicks.put(event.getPlayer().getUniqueId(), (long) Bukkit.getCurrentTick());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooks.remove(event.getPlayer().getUniqueId());
        entityClicks.remove(event.getPlayer().getUniqueId());
    }

    private static void pinOut(Player player) {
        player.getWorld().playSound(player.getLocation(), "craftov:throw.pin", SoundCategory.PLAYERS, 1f, 1f);
    }

    /** One grenade out of the hand, as thrown: counted as used, and every grenade locked for a second. */
    private ItemStack takeOne(Player player, ItemStack hand, ItemDef def) {
        ItemStack one = hand.clone();
        one.setAmount(1);
        hand.setAmount(hand.getAmount() - 1);
        if (!ItemFactory.isTest(one)) {
            plugin.raids().usedUp(player, def.id(), 1);
        }
        player.setCooldown(ItemFactory.THROW_GROUP, THROW_COOLDOWN);
        return one;
    }

    /** Held past its fuse: the grenade goes off at the thrower's middle. */
    private void goesOffInHand(Player player, Cook cook) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isGrenade(hand, cook.def)) {
            return;
        }
        player.clearActiveItem();
        takeOne(player, hand, cook.def);
        detonate(player, player.getLocation().add(0, player.getHeight() * 0.55, 0), cook.def.throwable(), cook.def.name());
    }

    /** Where a grenade leaves the hand: a little in front of the eyes, short of any wall there. */
    private static double[] throwOrigin(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        double reach = 0.4;
        RayTraceResult wall = eye.getWorld().rayTraceBlocks(eye, dir, reach + RADIUS, FluidCollisionMode.NEVER, true);
        if (wall != null) {
            reach = Math.max(0, wall.getHitPosition().distance(eye.toVector()) - RADIUS);
        }
        Vector at = eye.toVector().add(dir.multiply(reach));
        return new double[] {at.getX(), at.getY(), at.getZ()};
    }

    /** How fast a grenade leaves the hand: a gentle lob while sneaking. */
    private static double[] throwVelocity(Player player) {
        Vector v = player.getEyeLocation().getDirection().multiply(player.isSneaking() ? 0.6 : 1.15).add(new Vector(0, 0.15, 0));
        return new double[] {v.getX(), v.getY(), v.getZ()};
    }

    private Bounce bounce() {
        return new Bounce(plugin.getConfig().getDouble("throwables.bounce.wall", 0.35),
                plugin.getConfig().getDouble("throwables.bounce.floor", 0.25));
    }

    /** Sends a grenade flying from the thrower's hand; it goes off on tick {@code goesOff}. */
    private void launch(Player thrower, ItemStack stack, ItemDef def, long goesOff) {
        World world = thrower.getWorld();
        double[] pos = throwOrigin(thrower);
        double[] vel = throwVelocity(thrower);
        ItemDisplay display = world.spawn(new Location(world, pos[0], pos[1], pos[2]), ItemDisplay.class, d -> {
            d.setItemStack(stack);
            // Drawn as a dropped item is, always turned to the viewer so it never shows its thin edge.
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setTeleportDuration(2);
            d.setPersistent(false);
            d.getPersistentDataContainer().set(Keys.ENTITY_ROLE, PersistentDataType.STRING, "grenade");
        });
        Bounce bounce = bounce();
        ThrowableSpec spec = def.throwable();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!display.isValid()) {
                    cancel(); // the raid ended and took its world along
                    return;
                }
                if (Bukkit.getCurrentTick() >= goesOff) {
                    cancel();
                    display.remove();
                    detonate(thrower, new Location(world, pos[0], pos[1], pos[2]), spec, def.name());
                    return;
                }
                double impact = step(world, pos, vel, bounce);
                if (pos[1] < world.getMinHeight() - 16) {
                    cancel(); // fell out of the world
                    display.remove();
                    return;
                }
                Location at = new Location(world, pos[0], pos[1], pos[2]);
                if (impact >= CLINK) {
                    world.playSound(at, "minecraft:block.chain.hit", SoundCategory.PLAYERS, (float) Math.min(1.0, 0.3 + impact), 1.3f);
                }
                if (at.distanceSquared(display.getLocation()) > 1e-8) {
                    display.teleport(at);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Moves a grenade at {@code p} with velocity {@code v} (x, y, z, both changed in place) on by one
     * tick. Gravity comes first and drag last, as for a dropped item. In between it moves along each
     * axis in turn, as far as the first block in the way lets it (its centre stays {@link #RADIUS}
     * off the face): the speed into the face comes back times the bounce, and the speed along it is
     * scraped down. A floor bounce too slow to leave the ground settles instead, and on the ground
     * it rolls to a stop. In water it slows down. Returns the speed of the hardest impact, 0 if it
     * met nothing (lying on the floor is no impact).
     */
    private static double step(World world, double[] p, double[] v, Bounce bounce) {
        if (world.getBlockAt((int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2])).isLiquid()) {
            for (int i = 0; i < 3; i++) {
                v[i] *= 0.8;
            }
        }
        v[1] -= GRAVITY;
        double impact = 0;
        boolean ground = false;
        for (int axis : AXES) {
            double d = v[axis];
            if (Math.abs(d) < 1e-7) {
                continue;
            }
            double sign = Math.signum(d);
            Vector dir = new Vector(axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0);
            RayTraceResult hit = world.rayTraceBlocks(new Location(world, p[0], p[1], p[2]), dir, Math.abs(d) + RADIUS,
                    FluidCollisionMode.NEVER, true);
            if (hit == null) {
                p[axis] += d;
                continue;
            }
            Vector face = hit.getHitPosition();
            double gap = Math.abs((axis == 0 ? face.getX() : axis == 1 ? face.getY() : face.getZ()) - p[axis]);
            p[axis] += sign * Math.max(0, gap - RADIUS);
            boolean floor = axis == 1 && d < 0;
            double back = -d * (floor ? bounce.floor() : bounce.wall());
            if (floor) {
                ground = true;
                if (Math.abs(back) < SETTLE) {
                    back = 0;
                }
            }
            v[axis] = back;
            if (Math.abs(d) > SETTLE) { // a real impact, not just lying on the floor
                impact = Math.max(impact, Math.abs(d));
                for (int other = 0; other < 3; other++) {
                    if (other != axis) {
                        v[other] *= floor ? FLOOR_SCRAPE : WALL_SCRAPE;
                    }
                }
            }
        }
        for (int i = 0; i < 3; i++) {
            v[i] *= DRAG;
        }
        if (ground) {
            v[0] *= ROLL;
            v[2] *= ROLL;
            if (v[0] * v[0] + v[2] * v[2] < 1e-5) {
                v[0] = 0;
                v[2] = 0;
            }
        }
        return impact;
    }

    private void detonate(Player thrower, Location at, ThrowableSpec spec, String name) {
        switch (spec.type()) {
            case FRAG -> frag(thrower, at, spec, name);
            case SMOKE -> smoke(at, spec);
            case FLASH -> flash(at, spec);
        }
        plugin.ai().alert(at, 40, thrower);
    }

    /**
     * Shrapnel reaches whoever is within the radius (a sphere: Bukkit's nearby query is a box, whose
     * corners reach 1.7 times as far) along three lines, to the eyes, the middle of the body and the
     * legs. Each line no block cuts carries a third of the damage, less the farther that point is
     * from the blast, so a head showing over a crate still gets hurt, less than in the open, and only
     * someone wholly behind cover is spared. Each line lands on its part through the armour there
     * ({@link net.infnetwork.snowball.craftov.combat.DamageService#explosion}): shrapnel has little
     * penetration (throwables.frag.penetration) but wears armour hard (throwables.frag.armor-damage).
     */
    private void frag(Player thrower, Location at, ThrowableSpec spec, String name) {
        at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at, 30, 1.2, 0.6, 1.2, 0.05);
        at.getWorld().playSound(at, "craftov:throw.explode", SoundCategory.PLAYERS, 6f, 1f);
        int pen = plugin.getConfig().getInt("throwables.frag.penetration", 2);
        double armorDamage = plugin.getConfig().getDouble("throwables.frag.armor-damage", 1.5);
        for (LivingEntity entity : at.getWorld().getNearbyLivingEntities(at, spec.radius())) {
            if (entity.getPersistentDataContainer().has(Keys.ENTITY_ROLE)) {
                continue;
            }
            if (entity instanceof Player p && !plugin.raids().canBeShot(p)) {
                continue;
            }
            // Squadmates with friendly fire off are spared outright: recording the thrower first
            // let a downed mate who later bled out count as their kill, bounty and all. The
            // thrower is not spared, but nobody gets the credit for blowing yourself up.
            boolean self = entity == thrower;
            if (!self && !plugin.raids().canDamage(thrower, entity)) {
                continue;
            }
            List<DamageService.Blast> hits = new ArrayList<>(3);
            for (HitZone zone : HitZone.values()) {
                double damage = shrapnel(at, shrapnelTarget(entity, zone), spec);
                if (damage > 0) {
                    hits.add(new DamageService.Blast(zone, damage));
                }
            }
            if (hits.isEmpty()) {
                continue;
            }
            plugin.damage().explosion(thrower, entity, at, hits, name, pen, armorDamage);
            Vector push = entity.getLocation().add(0, entity.getHeight() / 2, 0).toVector().subtract(at.toVector());
            if (push.lengthSquared() > 1e-4) {
                entity.setVelocity(entity.getVelocity().add(push.normalize().multiply(0.4)));
            }
        }
    }

    /** The point of a body shrapnel flies at for each zone: the eyes, the middle and the legs. */
    private static Location shrapnelTarget(LivingEntity entity, HitZone zone) {
        return switch (zone) {
            case HEAD -> entity.getEyeLocation();
            case TORSO -> entity.getLocation().add(0, entity.getHeight() / 2, 0);
            case LEGS -> entity.getLocation().add(0, Math.min(0.4, entity.getHeight() / 4), 0);
        };
    }

    /**
     * The damage the shrapnel flying from {@code at} to {@code point} carries: a third of the
     * grenade's, falling off linearly to a tenth at the edge of the radius, and none beyond it or
     * when a block stands in the way.
     */
    private static double shrapnel(Location at, Location point, ThrowableSpec spec) {
        Vector to = point.toVector().subtract(at.toVector());
        double distance = to.length();
        if (distance > spec.radius()) {
            return 0;
        }
        if (distance > 1e-3) {
            RayTraceResult wall = at.getWorld().rayTraceBlocks(at, to.multiply(1 / distance), distance,
                    FluidCollisionMode.NEVER, true);
            if (wall != null && wall.getHitBlock() != null) {
                return 0;
            }
        }
        return spec.damage() / 3 * Math.max(0.1, 1 - distance / spec.radius());
    }

    private void smoke(Location at, ThrowableSpec spec) {
        at.getWorld().playSound(at, "craftov:throw.smoke", SoundCategory.PLAYERS, 2f, 1f);
        // The cloud below lasts 15 s and its particles linger a few seconds more; soldiers cannot see through it.
        plugin.ai().smoke(at, spec.radius(), 20L * 18);
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
     * How a flashbang going off at {@code at} reaches someone: null if they are out of range (a
     * sphere round the blast, measured to the eyes) or a block stands between the blast and their
     * eyes. Full strength at the blast, fading to combat.flashbang.edge-strength at the edge of the
     * radius.
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
            RayTraceResult wall = at.getWorld().rayTraceBlocks(at, dir, distance, FluidCollisionMode.NEVER, true);
            if (wall != null && wall.getHitBlock() != null) {
                return null;
            }
            facing = eye.getDirection().dot(dir.multiply(-1)) > 0.3;
        }
        double edge = plugin.getConfig().getDouble("combat.flashbang.edge-strength", 0.4);
        return new FlashHit(facing, 1 - (1 - edge) * Math.min(1, distance / Math.max(0.1, radius)));
    }
}
