package net.infnetwork.snowball.craftov.security;

import net.infnetwork.snowball.craftov.gui.Menu;
import net.infnetwork.snowball.craftov.item.Keys;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataContainer;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * Keeps vanilla storage out of the game. Every Craftov item is paper, so anything vanilla that holds
 * items would take loot as readily as a chest does: copper chests and shelves, item frames and
 * armour stands, chest minecarts and boats, and a villager would buy paper outright. A map author
 * only has to place one of them for players to stash, swap or sell what they carry.
 * <p>
 * So the rule is a whitelist, for everyone but builders in creative mode: the only screens that
 * open are the plugin's own menus ({@link Menu}); blocks that hold items or open a vanilla screen
 * do nothing when right-clicked ({@link #locked(Block)}); and the vanilla entities that hold items
 * cannot be used, nor hit to knock out or break off what they hold ({@link #locked(Entity)}).
 * Players (a downed squadmate is revived by clicking them) and the plugin's own entities (loot
 * boxes, bodies, lobby stations) are never locked.
 */
public final class VanillaGuardService implements Listener {

    /**
     * Blocks with a vanilla screen or use: workstations that hold nothing (a crafting table, an
     * anvil, a loom), and the common containers, which their state would give away as well.
     */
    private static final Set<Material> BLOCKED = EnumSet.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER, Material.CRAFTING_TABLE, Material.ANVIL,
            Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL, Material.ENCHANTING_TABLE, Material.BREWING_STAND,
            Material.LOOM, Material.CARTOGRAPHY_TABLE, Material.SMITHING_TABLE, Material.STONECUTTER, Material.GRINDSTONE,
            Material.LECTERN, Material.HOPPER, Material.DROPPER, Material.DISPENSER, Material.ENDER_CHEST, Material.BEACON,
            Material.CRAFTER, Material.DECORATED_POT, Material.JUKEBOX, Material.NOTE_BLOCK, Material.CAULDRON,
            Material.FLOWER_POT, Material.CHISELED_BOOKSHELF, Material.RESPAWN_ANCHOR, Material.BELL);

    /** Whether vanilla rules apply to this player: builders in creative mode place and fill these freely. */
    private static boolean guarded(@Nullable Player player) {
        return player != null && player.getGameMode() != GameMode.CREATIVE;
    }

    /**
     * Whether right-clicking this block must do nothing: a fixed list of blocks with a vanilla screen
     * or use, beds and shulker boxes, and anything that holds items. The last test catches every
     * container, including the copper chests and the shelves (which swap with the hand instead of
     * opening) of 1.21.9, and whatever later versions add; those two are also named outright.
     */
    public static boolean locked(Block block) {
        Material type = block.getType();
        String name = type.name();
        return BLOCKED.contains(type) || name.endsWith("_BED") || name.endsWith("SHULKER_BOX") || name.endsWith("_SHELF")
                || Tag.COPPER_CHESTS.isTagged(type) || block.getState(false) instanceof InventoryHolder;
    }

    /**
     * Whether this is a vanilla entity that holds items: item frames, armour stands, and anything
     * with an inventory (chest minecarts and boats, villagers and wandering traders, allays, horses).
     * Players are inventory holders too and stay usable; so do the plugin's own entities, which
     * carry a role or a station tag and are handled where they are made.
     */
    public static boolean locked(Entity entity) {
        if (entity instanceof HumanEntity) {
            return false;
        }
        PersistentDataContainer data = entity.getPersistentDataContainer();
        if (data.has(Keys.ENTITY_ROLE) || data.has(Keys.key("station"))) {
            return false;
        }
        return entity instanceof ItemFrame || entity instanceof ArmorStand || entity instanceof InventoryHolder;
    }

    /** Only the plugin's menus open: a vanilla screen (a container, a villager's trades) is refused. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && guarded(player)
                && !(event.getView().getTopInventory().getHolder(false) instanceof Menu)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUse(PlayerInteractEntityEvent event) {
        if (guarded(event.getPlayer()) && locked(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    /** The click at a point on an entity, which is what an armour stand acts on. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUseAt(PlayerInteractAtEntityEvent event) {
        if (guarded(event.getPlayer()) && locked(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (guarded(event.getPlayer()) && locked(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    /** A hit knocks the item out of a frame, and breaks an armour stand, which drops what it wears. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if ((event.getEntity() instanceof ItemFrame || event.getEntity() instanceof ArmorStand)
                && guarded(attacker(event.getDamager())) && locked(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** An empty frame or a painting knocked off the wall drops as an item too: the map's decor stays put. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(HangingBreakByEntityEvent event) {
        if (guarded(attacker(event.getRemover()))) {
            event.setCancelled(true);
        }
    }

    /** The player behind a hit: the attacker itself, or whoever shot the projectile. */
    private static @Nullable Player attacker(@Nullable Entity damager) {
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return damager instanceof Player player ? player : null;
    }
}
