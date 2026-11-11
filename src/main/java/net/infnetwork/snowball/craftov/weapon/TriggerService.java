package net.infnetwork.snowball.craftov.weapon;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.util.Nms;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
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
 */
public final class TriggerService implements Listener {

    private static final double DISTANCE = 3.2;
    /** Ticks without a swing before the trigger counts as released (covers lost abort packets). */
    private static final int SWING_TIMEOUT = 4;
    /** A single click on air (no capture block in reach) fires this long. */
    private static final int TAP_TICKS = 2;

    private static final class Capture {
        @Nullable World world;
        /** Read by the network thread (see Nms#protectCells), so it must be concurrent. */
        final Set<Long> cells = java.util.concurrent.ConcurrentHashMap.newKeySet();
        boolean protectedChannel;
        long lastInput = -100;
        long abortTick = -100;
        long tapUntil = -100;
        /** Swings until this tick come from Q, right-clicks or inventory use, not from the trigger. */
        long muteUntil = -100;
        boolean mining;
        boolean held;
        /** The server resent real blocks (it does after every right-click on a block): resend all cells. */
        boolean dirty;
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
            boolean active = canCapture(player);
            if (active) {
                updateCells(player, c);
            } else if (!c.cells.isEmpty()) {
                clearCells(player, c);
            }
            boolean wasHeld = c.held;
            boolean swinging = now - c.lastInput <= SWING_TIMEOUT;
            // Moving the crosshair to another block sends abort + start together; give that one tick.
            boolean digging = c.mining || now == c.abortTick;
            c.held = active && ((swinging && digging) || now <= c.tapUntil);
            if (c.held && !wasHeld) {
                plugin.weapons().triggerDown(player);
            } else if (!c.held && wasHeld) {
                plugin.weapons().triggerUp(player);
            }
        }
    }

    private void updateCells(Player player, Capture c) {
        if (!c.protectedChannel) {
            c.protectedChannel = true;
            Nms.protectCells(player, c.cells);
        }
        World world = player.getWorld();
        if (c.world != world) {
            c.cells.clear();
            c.world = world;
        }
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        Vector centre = eye.toVector().add(dir.clone().multiply(DISTANCE));
        int cx = centre.getBlockX();
        int cy = centre.getBlockY();
        int cz = centre.getBlockZ();
        double ax = Math.abs(dir.getX());
        double ay = Math.abs(dir.getY());
        double az = Math.abs(dir.getZ());
        BoundingBox body = player.getBoundingBox().expand(0.6);
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
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    continue;
                }
                Block block = world.getBlockAt(x, y, z);
                if (!block.getType().isAir() || body.overlaps(new BoundingBox(x, y, z, x + 1, y + 1, z + 1))) {
                    continue;
                }
                if (eye.toVector().distanceSquared(new Vector(x + 0.5, y + 0.5, z + 0.5)) > 4.3 * 4.3) {
                    continue;
                }
                wanted.add(key(x, y, z));
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
        if (event.getHand() != EquipmentSlot.HAND || !holdingGun(event.getPlayer())) {
            return;
        }
        Player player = event.getPlayer();
        Capture c = capture(player);
        long now = Bukkit.getCurrentTick();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            // Never cancel: a cancelled dig makes the server resend the real block, erasing the capture cell.
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            c.mining = true;
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
        if (event.getHand() == EquipmentSlot.HAND && holdingGun(event.getPlayer())
                && Bukkit.getCurrentTick() > capture(event.getPlayer()).muteUntil) {
            capture(event.getPlayer()).lastInput = Bukkit.getCurrentTick();
        }
    }

    /** Sent when the button is released (or the crosshair leaves the block, followed by a new start). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbort(BlockDamageAbortEvent event) {
        Capture c = captures.get(event.getPlayer().getUniqueId());
        if (c != null) {
            c.abortTick = Bukkit.getCurrentTick();
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

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        Capture c = captures.get(event.getPlayer().getUniqueId());
        if (c != null) {
            c.cells.clear();
            c.world = null;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        captures.remove(event.getPlayer().getUniqueId());
    }
}
