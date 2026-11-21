package net.infnetwork.snowball.craftov.inventory;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.gui.Menu;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.GameMode;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Enforces the carry layout on the player's own inventory screen. */
public final class CarryListener implements Listener {

    private final CraftovPlugin plugin;
    private final CarryService carry;
    private final ItemFactory items;

    public CarryListener(CraftovPlugin plugin, CarryService carry) {
        this.plugin = plugin;
        this.carry = carry;
        this.items = carry.items();
    }

    private static boolean managed(Player player) {
        return player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !managed(player)) {
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            return; // menus handle every click themselves
        }
        // Crafting grid and any foreign container top inventory are off limits.
        if (event.getClickedInventory() != null && event.getClickedInventory() != player.getInventory()) {
            if (event.getView().getType() == InventoryType.CRAFTING || event.getView().getType() == InventoryType.WORKBENCH) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND || event.getHotbarButton() == 40) {
            event.setCancelled(true);
            return;
        }
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (ItemFactory.isUi(current) || ItemFactory.isUi(cursor)) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory() == null) {
            // A click outside the window throws the cursor. Where throwing is not allowed, vanilla puts a
            // refused throw back with addItem and deletes whatever has no room, so the click is refused
            // instead and the item stays on the cursor.
            if (cursor != null && !cursor.isEmpty() && !mayDrop(player)) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getClickedInventory() != player.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (slot == CarryService.OFFHAND) {
            event.setCancelled(true);
            return;
        }
        if (violatesMeleeSlot(player, event, slot, current, cursor)) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() == InventoryAction.HOTBAR_SWAP) {
            ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
            if (ItemFactory.isUi(hotbar) || slot >= CarryService.FEET
                    || (ItemFactory.hasContents(hotbar) && slot < CarryService.FEET)
                    || ItemFactory.hasContents(current)) {
                event.setCancelled(true);
            }
            return;
        }
        if (slot == CarryService.LEGS || slot == CarryService.FEET) {
            event.setCancelled(true);
            handleContainerSlot(player, slot, event);
            return;
        }
        if (event.isShiftClick() && slot < CarryService.FEET) {
            ItemDef def = items.defOf(current);
            if (def != null && def.isContainer()) {
                event.setCancelled(true);
                int armorSlot = CarryService.containerSlot(def.category());
                ItemStack equipped = player.getInventory().getItem(armorSlot);
                if (equipped == null || equipped.isEmpty()) {
                    ItemStack moving = current.clone();
                    player.getInventory().setItem(slot, null);
                    carry.equip(player, moving);
                    player.updateInventory();
                } else {
                    Text.send(player, "<#FF8A80>请先卸下当前的" + def.category().displayName() + "。");
                }
                return;
            }
        }
        // Non-empty containers may only be equipped, held on the cursor, or stored in boxes.
        if (slot < CarryService.FEET && ItemFactory.hasContents(cursor)
                && (event.getAction().name().startsWith("PLACE") || event.getAction() == InventoryAction.SWAP_WITH_CURSOR)) {
            event.setCancelled(true);
            Text.send(player, "<#FF8A80>装有物品的胸挂/背包不能放进携行格，请直接装备或放进箱子。");
        }
    }

    /** Only melee weapons go into the melee slot, and they never leave it. */
    private boolean violatesMeleeSlot(Player player, InventoryClickEvent event, int slot, ItemStack current, ItemStack cursor) {
        if (event.getAction() == InventoryAction.HOTBAR_SWAP) {
            ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
            if (event.getHotbarButton() == CarryService.MELEE_SLOT || slot == CarryService.MELEE_SLOT) {
                return true;
            }
            return carry.isMelee(hotbar) || carry.isMelee(current);
        }
        if (slot == CarryService.MELEE_SLOT) {
            // Picking the knife up or dropping it is not allowed; swapping in another melee weapon is.
            return cursor == null || cursor.isEmpty() || !carry.isMelee(cursor);
        }
        return carry.isMelee(current) || carry.isMelee(cursor);
    }

    /**
     * Rig and backpack slots. The click is already cancelled, so vanilla never moves these stacks;
     * this resolves it at once against the cursor and slot as they are right now. It used to run a
     * tick later on stacks read at click time, so two clicks the server handled in the same tick
     * (lag, or a macro) both acted on the same stale stack and duplicated or deleted the container.
     */
    private void handleContainerSlot(Player player, int slot, InventoryClickEvent event) {
        ItemStack cursor = player.getItemOnCursor();
        ItemStack equipped = player.getInventory().getItem(slot);
        boolean holding = !cursor.isEmpty();
        boolean worn = equipped != null && !equipped.isEmpty();
        switch (event.getClick()) {
            // Two quick clicks reach the server as a click and a double click; both pick up or put on.
            case LEFT, RIGHT, DOUBLE_CLICK -> {
                if (holding) {
                    wearFromCursor(player, slot, cursor.clone(), worn);
                } else if (worn) {
                    ItemStack removed = carry.unequip(player, slot);
                    if (removed != null) {
                        player.setItemOnCursor(removed);
                    }
                }
            }
            case SHIFT_LEFT, SHIFT_RIGHT -> {
                if (!holding && worn) {
                    shiftUnequip(player, slot);
                }
            }
            case DROP, CONTROL_DROP -> {
                if (!holding && worn) {
                    dropWorn(player, slot);
                }
            }
            default -> {
                // Middle clicks and the like have nothing to do on these slots.
            }
        }
        player.updateInventory();
    }

    /** Puts the container on the cursor on; whatever was worn there goes onto the cursor. */
    private void wearFromCursor(Player player, int slot, ItemStack moving, boolean worn) {
        ItemDef def = items.defOf(moving);
        if (def == null || !def.isContainer() || CarryService.containerSlot(def.category()) != slot) {
            return;
        }
        ItemStack old = worn ? carry.unequip(player, slot) : null;
        player.setItemOnCursor(old);
        carry.equip(player, moving);
    }

    /** Shift-click takes off an empty rig or backpack into a free carry cell. */
    private void shiftUnequip(Player player, int slot) {
        PlayerInventory inv = player.getInventory();
        int start = slot == CarryService.LEGS ? CarryService.RIG_START : CarryService.PACK_START;
        int cap = slot == CarryService.LEGS ? carry.rigCapacity(player) : carry.backpackCapacity(player);
        for (int i = 0; i < cap; i++) {
            ItemStack cell = inv.getItem(start + i);
            if (cell != null && !cell.isEmpty() && !ItemFactory.isUi(cell)) {
                Text.send(player, "<#FF8A80>里面还有物品，请用鼠标直接拿起再放进箱子或丢弃。");
                return;
            }
        }
        ItemStack removed = carry.unequip(player, slot);
        if (removed != null) {
            ItemStack left = carry.give(player, removed, false);
            if (left != null) {
                carry.equip(player, left);
            }
        }
    }

    /**
     * Q on the worn rig or backpack drops it with everything still packed inside, but only for a
     * raider who is up: in the lobby nobody can pick it up again, and a downed player keeps their
     * gear. The drop is then announced like a vanilla one, so every other drop rule still has its
     * say; if one of them refuses, the container goes straight back on.
     */
    private void dropWorn(Player player, int slot) {
        RaidPlayer rp = plugin.raids().session(player);
        if (rp == null || rp.state != RaidPlayer.State.ALIVE) {
            String what = (slot == CarryService.LEGS ? Category.RIG : Category.BACKPACK).displayName();
            Text.send(player, rp == null ? "<#FF8A80>只有在对局中才能丢弃" + what + "。"
                    : rp.state == RaidPlayer.State.DOWNED ? "<#FF8A80>倒地时不能丢弃" + what + "。"
                    : "<#FF8A80>现在不能丢弃" + what + "。");
            return;
        }
        ItemStack removed = carry.unequip(player, slot);
        if (removed == null) {
            return;
        }
        Item dropped = player.getWorld().dropItem(player.getEyeLocation().subtract(0, 0.3, 0), removed,
                item -> item.setVelocity(player.getLocation().getDirection().multiply(0.3)));
        // The constructor is marked internal, but firing it is the only way to let the lobby, the range
        // and the raid rules veto this drop the way they veto any other.
        if (!dropped.isValid() || !new PlayerDropItemEvent(player, dropped).callEvent()) {
            dropped.remove();
            carry.equip(player, removed);
        }
    }

    /** Throwing an item is allowed only for a raider who is up; the lobby, the range and the raid refuse it otherwise. */
    private boolean mayDrop(Player player) {
        RaidPlayer rp = plugin.raids().session(player);
        return rp != null && rp.state == RaidPlayer.State.ALIVE;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !managed(player)) {
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
            return;
        }
        if (ItemFactory.hasContents(event.getOldCursor()) || ItemFactory.isUi(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize && (event.getView().getType() == InventoryType.CRAFTING || event.getView().getType() == InventoryType.WORKBENCH)) {
                event.setCancelled(true);
                return;
            }
            int slot = event.getView().convertSlot(raw);
            if ((raw >= topSize || event.getView().getType() == InventoryType.CRAFTING)
                    && (slot == CarryService.MELEE_SLOT || carry.isMelee(event.getOldCursor()))) {
                event.setCancelled(true);
                return;
            }
            if (raw >= topSize || event.getView().getType() == InventoryType.CRAFTING) {
                if (slot >= CarryService.FEET) {
                    event.setCancelled(true);
                    return;
                }
                ItemStack existing = player.getInventory().getItem(slot);
                if (ItemFactory.isUi(existing)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRecipeBook(com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent event) {
        // Craftov items are paper underneath; never let the recipe book move them into a crafting grid.
        if (managed(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (ItemFactory.isUi(event.getItemDrop().getItemStack()) || carry.isMelee(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        // F is repurposed (fire mode / skills); the off hand is never used.
        event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (managed(player)) {
            // A player who disconnects is no longer there to pick a thrown cursor back up.
            settleCursor(player, event.getReason() == InventoryCloseEvent.Reason.DISCONNECT);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !managed(player)) {
                return;
            }
            carry.enforceContainers(player);
            carry.refreshLocks(player);
        });
    }

    /**
     * Leaving with the inventory screen open fires no close event, and the lobby and the raid both
     * save the inventory before vanilla hands the cursor back, so the cursor is settled first.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        if (managed(event.getPlayer())) {
            settleCursor(event.getPlayer(), true);
        }
    }

    /**
     * Puts the cursor back on the player before vanilla does. Right after the close event vanilla puts
     * it into the first free cell, or, with none free, throws it; the lobby, the range and a downed
     * raider refuse that throw, and a refused throw is put back with addItem, which deletes whatever
     * has no room. A packed rig or backpack must not land in a cell either. So unless vanilla's way is
     * safe (a raider who is up, holding anything but a packed container), the cursor is settled here:
     * a packed container goes back on when its slot is free, anything else goes into a free cell, and
     * what has no room is spilled (mailed outside a raid, dropped at the player's feet in one).
     */
    private void settleCursor(Player player, boolean always) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor.isEmpty()) {
            return;
        }
        if (ItemFactory.isUi(cursor)) {
            player.setItemOnCursor(null);
            return;
        }
        boolean packed = ItemFactory.hasContents(cursor);
        if (!always && !packed && mayDrop(player)) {
            return;
        }
        ItemStack moving = cursor.clone();
        player.setItemOnCursor(null);
        ItemDef def = items.defOf(moving);
        if (packed && def != null && def.isContainer()) {
            ItemStack worn = player.getInventory().getItem(CarryService.containerSlot(def.category()));
            if (worn == null || worn.isEmpty()) {
                carry.equip(player, moving);
                return;
            }
        }
        carry.give(player, moving, true);
    }
}
