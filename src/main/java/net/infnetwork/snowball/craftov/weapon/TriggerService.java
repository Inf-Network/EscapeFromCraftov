package net.infnetwork.snowball.craftov.weapon;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.util.Nms;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Left-mouse trigger for guns. The vanilla client sends nothing while the attack button is held,
 * unless it is "mining" a block. So while a gun is out, a 3x3 patch of client-only cobweb (invisible
 * via the resource pack, no collision, never breaks because guns zero the break speed) is kept in
 * the air cells about three blocks in front of the crosshair. Holding left-click then produces an
 * arm swing every tick and an abort packet on release, which is exactly a trigger.
 *
 * <p>The abort is what says the button went up. Swings only show the client is still there: a lag
 * spike stops them for a moment and then delivers them all at once, so a hold only times out after
 * a full second without one, and that kind of release does not count as letting go of a
 * semi-automatic trigger.
 *
 * <p>Only a real dig start presses the trigger. In adventure mode (raids, the range) the server also
 * turns every arm swing into a left-click on whatever block its own ray finds, and the client swings
 * after Q, after opening a door or pulling a lever, and after some menu clicks, with no abort ever
 * following. The network layer reports dig starts and swings in the order they came (see
 * {@link Nms.DigInputs}), so each left-click on a block is matched to the packet that caused it.
 *
 * <p>The client only digs while its crosshair lands on a block before anything else. An entity in
 * front of the cobweb wins that pick: the client sends one attack (a short tap, see {@link #tap})
 * and then nothing until the button is let go, so a spray at a target two blocks away used to stop
 * after two or three rounds. Each tick the server traces the crosshair for the nearest entity the
 * client could pick, and when one is that close, the cells go half a block in front of it instead.
 * Only when no cell fits between the body and the entity does the click still reach the entity:
 * from about 1.9 blocks out one always does, closer it depends on where block edges fall.
 *
 * <p>The cobweb is real on the client, and a body inside one is slowed to a crawl and stops
 * falling. The server only takes a cell away a round trip after the client walked or fell into
 * it, so cells keep clear of the body by how far it may get in that time: its recent movement per
 * tick times the ping in ticks plus two, and while falling none goes below the feet.
 */
public final class TriggerService implements Listener {

    private static final double DISTANCE = 3.2;
    /** Cells go this far in front of an entity on the crosshair, for its movement and the client's lag. */
    private static final double LEAD = 0.5;
    /** ...and never less than this: the client must still reach the cell before the entity. */
    private static final double LEAST_LEAD = 0.15;
    /** Entities this close along the crosshair get the cells in front of them; farther ones are behind the usual cells. */
    private static final double REACH = DISTANCE + LEAD;
    /** Nearest to the eye a cell in front of an entity is looked for (nearer ones are inside the body). */
    private static final double NEAREST = 0.3;
    /** No cell: the {@link #key} of a cell beyond the world border. */
    private static final long NONE = Long.MIN_VALUE;
    /**
     * Ticks without a swing before a hold over a block counts as released anyway. Only a fallback: the
     * abort packet is the release, and lag spikes stall swings for longer than a few ticks.
     */
    private static final int SWING_TIMEOUT = 20;
    /** A single click on air (no capture block in reach) fires this long. */
    private static final int TAP_TICKS = 2;
    /** Blocks of clearance kept around a body standing still. */
    private static final double BODY_MARGIN = 0.25;
    /** Ticks of movement the clearance looks back over (movement packets arrive unevenly). */
    private static final int MOTION_WINDOW = 3;
    /** Longest step per tick believed: about the terminal speed of a fall. Anything longer is a teleport. */
    private static final double MAX_STEP = 4.0;
    /** Most ticks of ping the clearance allows for, so a bad connection does not push every cell away. */
    private static final double MAX_LEAD = 12;
    /** An arm swing in {@link Capture#inputs} (no block key is this: it is beyond the world border). */
    private static final long SWING = Long.MIN_VALUE + 1;
    /**
     * Ticks without a dig start or swing read before what is left in {@link Capture#inputs} is dropped:
     * by then every packet read has been handled, so leftovers (a swing the server ignored while the
     * player was dead) would only misalign the matching.
     */
    private static final int INPUTS_QUIET = 10;

    private static final class Capture implements Nms.DigInputs {
        @Nullable World world;
        /** Read by the network thread (see Nms#protectCells), so it must be concurrent. */
        final Set<Long> cells = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /**
         * Dig starts (block keys) and swings ({@link #SWING}) in the order the client sent them, added on
         * the network thread before the server handles each packet and taken off as their events come.
         */
        final java.util.Queue<Long> inputs = new java.util.concurrent.ConcurrentLinkedQueue<>();
        /** Starts and swings read so far (network thread); a quiet spell lets the main thread drop leftovers. */
        final java.util.concurrent.atomic.AtomicLong reads = new java.util.concurrent.atomic.AtomicLong();
        long lastReads;
        int quiet;
        boolean protectedChannel;
        long lastInput = -100;
        long abortTick = -100;
        long abortCell;
        long tapUntil = -100;
        /** Swings until this tick come from Q, right-clicks or inventory use, not from the trigger. */
        long muteUntil = -100;
        boolean mining;
        /**
         * A dig started since the last tick. A click shorter than a client tick sends start and abort
         * together, and the server reads both before its next tick: this still holds the trigger for one.
         */
        boolean pressed;
        /** The block the client said it started digging (see {@link #key}); its abort only counts in reach. */
        long digCell;
        boolean held;
        /**
         * The hold timed out without an abort: the gun was told the trigger went up, but not that it was
         * let go, so a semi-automatic will not fire again until the abort (or a fresh click) comes.
         */
        boolean stale;
        /** The server resent real blocks (it does after every right-click on a block): resend all cells. */
        boolean dirty;
        /** Where the player was last tick, for {@link #steps}; null when there is nothing to measure from. */
        @Nullable Location lastAt;
        /** Movement (x, y, z) in each of the last {@link #MOTION_WINDOW} ticks. */
        final double[][] steps = new double[MOTION_WINDOW][3];
        int nextStep;

        @Override
        public void started(long pos) {
            inputs.offer(pos);
            reads.incrementAndGet();
        }

        @Override
        public void swung() {
            inputs.offer(SWING);
            reads.incrementAndGet();
        }
    }

    private final CraftovPlugin plugin;
    private final BlockData cobweb = Material.COBWEB.createBlockData();
    private final Map<UUID, Capture> captures = new HashMap<>();

    public TriggerService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private Capture capture(Player player) {
        return captures.computeIfAbsent(player.getUniqueId(), k -> new Capture());
    }

    /** True while the player is holding the trigger (left mouse) down. */
    public boolean held(Player player) {
        Capture c = captures.get(player.getUniqueId());
        return c != null && c.held;
    }

    /** True while capture cells are out for this player: a right-click may land on one instead of what is behind it. */
    public boolean capturing(Player player) {
        Capture c = captures.get(player.getUniqueId());
        return c != null && !c.cells.isEmpty();
    }

    private boolean holdingGun(Player player) {
        ItemDef def = plugin.items().defOf(player.getInventory().getItemInMainHand());
        return def != null && def.isGun();
    }

    private boolean canCapture(Player player) {
        return holdingGun(player) && player.getGameMode() != GameMode.SPECTATOR && !player.isDead()
                && !plugin.downed().isDowned(player)
                && (plugin.raids().isInRaid(player) || plugin.range().isIn(player));
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        long now = Bukkit.getCurrentTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Capture c = capture(player);
            track(player, c);
            settleInputs(c);
            boolean active = canCapture(player);
            if (active) {
                updateCells(player, c);
            } else if (!c.cells.isEmpty()) {
                clearCells(player, c);
            }
            if (c.mining && outOfReach(player, c.digCell)) {
                // The server drops an abort for a block out of reach before any event sees it (a fall or a
                // shove with the button held), and the client cannot still be digging a block that far off.
                c.mining = false;
            }
            boolean wasHeld = c.held;
            boolean heard = now - c.lastInput <= SWING_TIMEOUT;
            // Moving the crosshair to another block sends abort + start together; give that one tick.
            boolean digging = c.mining || now == c.abortTick;
            if (c.stale && !digging) {
                // The abort a timed-out hold was waiting for: the trigger has really been let go now.
                c.stale = false;
                plugin.weapons().triggerUp(player, true);
            }
            c.held = active && ((heard && digging) || now <= c.tapUntil || c.pressed);
            c.pressed = false;
            if (c.held && !wasHeld) {
                plugin.weapons().triggerDown(player);
            } else if (!c.held && wasHeld) {
                c.stale = active && digging && now > c.tapUntil;
                plugin.weapons().triggerUp(player, !c.stale);
            }
        }
    }

    /** Drops what is left of {@link Capture#inputs} once the client has been quiet for a while. */
    private static void settleInputs(Capture c) {
        long reads = c.reads.get();
        if (reads != c.lastReads) {
            c.lastReads = reads;
            c.quiet = 0;
        } else if (++c.quiet >= INPUTS_QUIET && !c.inputs.isEmpty()) {
            c.inputs.clear();
        }
    }

    /**
     * Whether a left-click on {@code pos} came from a dig start (true) or from the server's own ray for
     * an arm swing (false), or null if that cannot be told (the network hook is missing, or the packet
     * was read before it was in place). Events come in packet order, so the click belongs to the oldest
     * input still queued: a swing, or a start at that block. Starts at other blocks before it were
     * dropped by the server without an event (out of reach) and are skipped.
     */
    private static @Nullable Boolean fromDigStart(Capture c, long pos) {
        for (Long head = c.inputs.peek(); head != null; head = c.inputs.peek()) {
            if (head == SWING) {
                return false; // taken off by its PlayerArmSwingEvent, which follows this one
            }
            c.inputs.poll();
            if (head == pos) {
                return true;
            }
        }
        return null;
    }

    /** Records how far the player moved since last tick (see {@link #clearance}). */
    private static void track(Player player, Capture c) {
        Location at = player.getLocation();
        double[] step = c.steps[c.nextStep];
        c.nextStep = (c.nextStep + 1) % MOTION_WINDOW;
        if (c.lastAt != null && c.lastAt.getWorld() == at.getWorld()) {
            step[0] = Math.clamp(at.getX() - c.lastAt.getX(), -MAX_STEP, MAX_STEP);
            step[1] = Math.clamp(at.getY() - c.lastAt.getY(), -MAX_STEP, MAX_STEP);
            step[2] = Math.clamp(at.getZ() - c.lastAt.getZ(), -MAX_STEP, MAX_STEP);
        } else {
            for (double[] old : c.steps) {
                java.util.Arrays.fill(old, 0);
            }
        }
        c.lastAt = at;
    }

    /**
     * The space the player's body may reach before the client hears about a new cell: the hitbox plus
     * a margin, stretched in each direction it moved lately by that step times the ping in ticks plus
     * two, and further down while airborne for gravity over that time.
     */
    private static BoundingBox clearance(Player player, Capture c) {
        double lead = Math.min(MAX_LEAD, player.getPing() / 50.0 + 2);
        double[] low = new double[3];
        double[] high = new double[3];
        for (double[] step : c.steps) {
            for (int i = 0; i < 3; i++) {
                low[i] = Math.min(low[i], step[i]);
                high[i] = Math.max(high[i], step[i]);
            }
        }
        double fall = player.isOnGround() ? 0 : Math.min(MAX_STEP, 0.04 * lead * lead);
        return player.getBoundingBox().expand(BODY_MARGIN)
                .expand(-low[0] * lead, -low[1] * lead + fall, -low[2] * lead, high[0] * lead, high[1] * lead, high[2] * lead);
    }

    /** Airborne and on the way down: the next ticks take the body below where its feet are now. */
    private static boolean falling(Player player, Capture c) {
        if (player.isOnGround()) {
            return false;
        }
        double[] last = c.steps[Math.floorMod(c.nextStep - 1, MOTION_WINDOW)];
        return last[1] < 0;
    }

    /** Mirrors the server's reach check on dig packets: block interaction range plus one, eye to block. */
    private static boolean outOfReach(Player player, long cell) {
        var attribute = player.getAttribute(org.bukkit.attribute.Attribute.BLOCK_INTERACTION_RANGE);
        double reach = (attribute == null ? 4.5 : attribute.getValue()) + 1.0;
        Vector eye = player.getEyeLocation().toVector();
        int x = kx(cell);
        int y = ky(cell);
        int z = kz(cell);
        double dx = Math.max(Math.max(x - eye.getX(), eye.getX() - (x + 1)), 0);
        double dy = Math.max(Math.max(y - eye.getY(), eye.getY() - (y + 1)), 0);
        double dz = Math.max(Math.max(z - eye.getZ(), eye.getZ() - (z + 1)), 0);
        return dx * dx + dy * dy + dz * dz >= reach * reach;
    }

    /** True while a hold timed out and the gun still waits for the trigger to be let go. */
    public boolean stale(Player player) {
        Capture c = captures.get(player.getUniqueId());
        return c != null && c.stale;
    }

    private void updateCells(Player player, Capture c) {
        if (!c.protectedChannel) {
            c.protectedChannel = true;
            Nms.protectCells(player, c.cells, c);
        }
        World world = player.getWorld();
        if (c.world != world) {
            c.cells.clear();
            c.world = world;
        }
        Location eye = player.getEyeLocation();
        Vector from = eye.toVector();
        Vector dir = eye.getDirection();
        BoundingBox body = clearance(player, c);
        double feet = player.getLocation().getY();
        boolean falling = falling(player, c);
        Vector centre = from.clone().add(dir.clone().multiply(DISTANCE));
        int cx = centre.getBlockX();
        int cy = centre.getBlockY();
        int cz = centre.getBlockZ();
        RayTraceResult target = world.rayTrace(eye, dir, REACH, FluidCollisionMode.NEVER, false, 0.0,
                e -> pickable(player, e) && !downedMate(player, e));
        if (target != null && target.getHitEntity() != null) {
            long cell = inFront(world, from, dir, target.getHitPosition().distance(from), body, falling, feet);
            if (cell != NONE) {
                cx = kx(cell);
                cy = ky(cell);
                cz = kz(cell);
            }
        }
        double ax = Math.abs(dir.getX());
        double ay = Math.abs(dir.getY());
        double az = Math.abs(dir.getZ());
        Set<Long> wanted = new HashSet<>();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                int x = cx;
                int y = cy;
                int z = cz;
                if (ax >= ay && ax >= az) {
                    y += a;
                    z += b;
                } else if (ay >= ax && ay >= az) {
                    x += a;
                    z += b;
                } else {
                    x += a;
                    y += b;
                }
                if (fits(world, x, y, z, from, body, falling, feet)) {
                    wanted.add(key(x, y, z));
                }
            }
        }
        if (c.dirty) {
            c.dirty = false;
            List<BlockState> changes = new ArrayList<>();
            for (long cell : c.cells) {
                if (!wanted.contains(cell)) {
                    changes.add(world.getBlockAt(kx(cell), ky(cell), kz(cell)).getState());
                }
            }
            for (long cell : wanted) {
                BlockState state = world.getBlockAt(kx(cell), ky(cell), kz(cell)).getState();
                state.setBlockData(cobweb);
                changes.add(state);
            }
            c.cells.clear();
            c.cells.addAll(wanted);
            if (!changes.isEmpty()) {
                player.sendBlockChanges(changes);
            }
            return;
        }
        if (wanted.equals(c.cells)) {
            return;
        }
        List<BlockState> changes = new ArrayList<>();
        for (long cell : c.cells) {
            if (!wanted.contains(cell)) {
                changes.add(world.getBlockAt(kx(cell), ky(cell), kz(cell)).getState());
            }
        }
        for (long cell : wanted) {
            if (!c.cells.contains(cell)) {
                BlockState state = world.getBlockAt(kx(cell), ky(cell), kz(cell)).getState();
                state.setBlockData(cobweb);
                changes.add(state);
            }
        }
        c.cells.clear();
        c.cells.addAll(wanted);
        if (!changes.isEmpty()) {
            player.sendBlockChanges(changes);
        }
    }

    /**
     * The cell to centre the patch on so that the crosshair enters cobweb before an entity {@code hit}
     * blocks away: of the cells the crosshair passes through that fit, the farthest it enters at least
     * {@link #LEAD} short of the entity, else the nearest it enters at least {@link #LEAST_LEAD} short.
     * {@link #NONE} if there is no room: the usual cells stay, and the client attacks the entity (a tap).
     */
    private static long inFront(World world, Vector from, Vector dir, double hit, BoundingBox body, boolean falling, double feet) {
        long lead = NONE;
        long close = NONE;
        long last = NONE;
        for (double t = NEAREST; t < hit; t += 0.05) {
            int x = (int) Math.floor(from.getX() + dir.getX() * t);
            int y = (int) Math.floor(from.getY() + dir.getY() * t);
            int z = (int) Math.floor(from.getZ() + dir.getZ() * t);
            long cell = key(x, y, z);
            if (cell == last) {
                continue;
            }
            last = cell;
            double in = Math.max(entry(from.getX(), dir.getX(), x), Math.max(entry(from.getY(), dir.getY(), y), entry(from.getZ(), dir.getZ(), z)));
            if (in > hit - LEAST_LEAD) {
                break;
            }
            if (!fits(world, x, y, z, from, body, falling, feet)) {
                continue;
            }
            if (in <= hit - LEAD) {
                lead = cell;
            } else if (close == NONE) {
                close = cell;
            }
        }
        return lead != NONE ? lead : close;
    }

    /** Where a ray from {@code from} going {@code dir} along one axis enters the cell starting at {@code low}. */
    private static double entry(double from, double dir, int low) {
        if (Math.abs(dir) < 1e-9) {
            return Double.NEGATIVE_INFINITY;
        }
        return Math.min((low - from) / dir, (low + 1 - from) / dir);
    }

    /** A cell the capture block may go in: air, clear of the body (see {@link #clearance}) and within reach. */
    private static boolean fits(World world, int x, int y, int z, Vector eye, BoundingBox body, boolean falling, double feet) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight() || falling && y < feet) {
            return false;
        }
        if (!world.getBlockAt(x, y, z).getType().isAir() || body.overlaps(new BoundingBox(x, y, z, x + 1, y + 1, z + 1))) {
            return false;
        }
        return eye.distanceSquared(new Vector(x + 0.5, y + 0.5, z + 0.5)) <= 4.3 * 4.3;
    }

    /**
     * What the client's crosshair can land on (vanilla's "pickable"): living things, hitboxes of loot boxes
     * and racks, bodies, other players. Not the player itself.
     */
    public boolean pickable(Player player, Entity entity) {
        if (entity == player || !player.canSee(entity) || entity instanceof Display || entity instanceof Item
                || entity instanceof ExperienceOrb || entity instanceof Projectile || entity instanceof AreaEffectCloud
                || entity instanceof Marker || entity instanceof ArmorStand stand && stand.isMarker()) {
            return false;
        }
        return !(entity instanceof Player other) || other.getGameMode() != GameMode.SPECTATOR;
    }

    /**
     * A downed squad-mate: no capture cells go in front of one, since they are revived with a right-click
     * on them and cannot be shot anyway.
     */
    public boolean downedMate(Player player, Entity entity) {
        return entity instanceof Player other && plugin.downed().isDowned(other) && plugin.raids().sameSquad(player, other);
    }

    private void clearCells(Player player, Capture c) {
        if (c.world != null && c.world == player.getWorld()) {
            List<BlockState> changes = new ArrayList<>();
            for (long cell : c.cells) {
                changes.add(c.world.getBlockAt(kx(cell), ky(cell), kz(cell)).getState());
            }
            if (!changes.isEmpty()) {
                player.sendBlockChanges(changes);
            }
        }
        c.cells.clear();
    }

    /** Drops capture cells immediately (inventory screens, raid transitions). */
    public void reset(Player player) {
        Capture c = captures.get(player.getUniqueId());
        if (c != null) {
            clearCells(player, c);
            c.held = false;
            c.mining = false;
            c.pressed = false;
            c.stale = false;
            c.lastInput = -100;
            c.tapUntil = -100;
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int kx(long key) {
        return (int) (key >> 38);
    }

    private static int ky(long key) {
        int y = (int) (key & 0xFFF);
        return y >= 0x800 ? y - 0x1000 : y;
    }

    private static int kz(long key) {
        return (int) (key << 26 >> 38);
    }

    // ------------------------------------------------------------------ input

    /** Start of "mining": the button went down over a capture cell or a real block. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLeftClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        Boolean dig = null;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && block != null) {
            // Matched for every player and item, so the queue stays in step with the packets.
            dig = fromDigStart(capture(player), key(block.getX(), block.getY(), block.getZ()));
        }
        if (event.getHand() != EquipmentSlot.HAND || !holdingGun(player)) {
            return;
        }
        Capture c = capture(player);
        long now = Bukkit.getCurrentTick();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            // Never cancel: a cancelled dig makes the server resend the real block, erasing the capture cell.
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            // The server's ray for a swing only ever lands on a real block, never on a capture cell (air
            // here). Without the network hook, swings right after Q, a door or a menu are the stray ones.
            boolean real = block == null || block.getType().isAir() || (dig != null ? dig : now > c.muteUntil);
            if (!real) {
                return;
            }
            c.mining = true;
            c.pressed = true;
            if (block != null) {
                c.digCell = key(block.getX(), block.getY(), block.getZ());
                if (c.stale && c.abortTick == now && c.abortCell == c.digCell) {
                    // Let go and pressed again on the same block: a real click, even if it arrives in
                    // the same burst of late packets as the abort (moving the crosshair names a new block).
                    c.stale = false;
                    plugin.weapons().triggerUp(player, true);
                }
            }
            c.lastInput = now;
        } else if (event.getAction() == Action.LEFT_CLICK_AIR) {
            if (now <= c.muteUntil) {
                return;
            }
            // Swings while digging a capture cell also arrive as "air" clicks (the server sees air there).
            c.lastInput = now;
            if (!c.mining) {
                c.tapUntil = Math.max(c.tapUntil, now + TAP_TICKS);
            }
        }
    }

    /** Vanilla answers every use-on-block with real block updates for that block and its neighbour. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRightClickBlock(PlayerInteractEvent event) {
        // Aiming is a held right-click on air or plain blocks, so only interactable blocks mute.
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().isInteractable()) {
            mute(event.getPlayer());
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Capture c = captures.get(event.getPlayer().getUniqueId());
            if (c != null && !c.cells.isEmpty() && !Nms.ready()) {
                c.dirty = true;
            }
        }
    }

    /** The client swings every tick while the button is held over a block. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerArmSwingEvent event) {
        Capture c = capture(event.getPlayer());
        // This swing's turn in the queue (and any start before it the server dropped without an event).
        Long head;
        do {
            head = c.inputs.poll();
        } while (head != null && head != SWING);
        if (event.getHand() == EquipmentSlot.HAND && holdingGun(event.getPlayer())
                && Bukkit.getCurrentTick() > c.muteUntil) {
            c.lastInput = Bukkit.getCurrentTick();
        }
    }

    /** Sent when the button is released (or the crosshair leaves the block, followed by a new start). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbort(BlockDamageAbortEvent event) {
        Capture c = captures.get(event.getPlayer().getUniqueId());
        if (c != null) {
            c.abortTick = Bukkit.getCurrentTick();
            c.abortCell = key(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
            c.mining = false;
        }
    }

    /**
     * The client swings its arm after dropping an item (Q), using an item or block (right-click) and
     * after some inventory actions; Paper reports those swings as left-clicks on air. Ignore them briefly.
     */
    public void mute(Player player) {
        Capture c = capture(player);
        long now = Bukkit.getCurrentTick();
        c.muteUntil = now + 3;
        c.tapUntil = -100;
        if (!c.mining) {
            c.lastInput = -100;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        mute(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            mute(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            mute(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            mute(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        mute(event.getPlayer());
    }

    /** Clicking straight at a close entity sends an attack instead of a dig: treat it as a tap. */
    public void tap(Player player) {
        Capture c = capture(player);
        long now = Bukkit.getCurrentTick();
        c.lastInput = now;
        c.tapUntil = Math.max(c.tapUntil, now + TAP_TICKS);
    }

    /** A teleport is not movement: measure from where the player lands. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Capture c = captures.get(event.getPlayer().getUniqueId());
        if (c != null) {
            c.lastAt = null;
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Capture c = captures.get(event.getPlayer().getUniqueId());
        if (c != null) {
            c.lastAt = null;
        }
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        Capture c = captures.get(event.getPlayer().getUniqueId());
        if (c != null) {
            c.cells.clear();
            c.world = null;
            // Whatever the client was digging is in the old world; its abort will not name a block here.
            c.mining = false;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        captures.remove(event.getPlayer().getUniqueId());
    }
}
