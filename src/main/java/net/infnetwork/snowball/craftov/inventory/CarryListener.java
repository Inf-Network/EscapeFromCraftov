package net.infnetwork.snowball.craftov.inventory;

import net.infnetwork.snowball.craftov.gui.Menu;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.GameMode;
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
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/** Enforces the carry layout on the player's own inventory screen. */
public final class CarryListener implements Listener {

    private final Plugin plugin;
    private final CarryService carry;
    private final ItemFactory items;

    public CarryListener(Plugin plugin, CarryService carry) {
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
        if (event.getClickedInventory() != player.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (slot == CarryService.OFFHAND) {
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

    private void handleContainerSlot(Player player, int slot, InventoryClickEvent event) {
        PlayerInventory inv = player.getInventory();
        ItemStack cursor = player.getItemOnCursor();
        ItemStack equipped = inv.getItem(slot);
        boolean shift = event.isShiftClick();
        boolean drop = event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (drop && cursor.isEmpty()) {
                // Q on the equipped rig/backpack drops it with everything still packed inside.
                ItemStack removed = carry.unequip(player, slot);
                if (removed != null) {
                    player.getWorld().dropItem(player.getEyeLocation().subtract(0, 0.3, 0), removed,
                            item -> item.setVelocity(player.getLocation().getDirection().multiply(0.3)));
                    player.updateInventory();
                }
                return;
            }
            if (cursor.isEmpty()) {
                if (equipped == null || equipped.isEmpty()) {
                    return;
                }
                if (shift) {
                    boolean emptyCells = true;
                    int start = slot == CarryService.LEGS ? CarryService.RIG_START : CarryService.PACK_START;
                    int cap = slot == CarryService.LEGS ? carry.rigCapacity(player) : carry.backpackCapacity(player);
                    for (int i = 0; i < cap; i++) {
                        ItemStack cell = inv.getItem(start + i);
                        if (cell != null && !cell.isEmpty() && !ItemFactory.isUi(cell)) {
                            emptyCells = false;
                            break;
                        }
                    }
                    if (!emptyCells) {
                        Text.send(player, "<#FF8A80>里面还有物品，请用鼠标直接拿起再放进箱子或丢弃。");
                        return;
                    }
                    ItemStack removed = carry.unequip(player, slot);
                    if (removed != null) {
                        ItemStack left = carry.give(player, removed, false);
                        if (left != null) {
                            carry.equip(player, left);
                        }
                    }
                } else {
                    ItemStack removed = carry.unequip(player, slot);
                    player.setItemOnCursor(removed);
                }
            } else {
                ItemDef def = items.defOf(cursor);
                if (def == null || !def.isContainer() || CarryService.containerSlot(def.category()) != slot) {
                    return;
                }
                ItemStack old = (equipped == null || equipped.isEmpty()) ? null : carry.unequip(player, slot);
                player.setItemOnCursor(old);
                carry.equip(player, cursor);
            }
            player.updateInventory();
        });
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
        if (ItemFactory.isUi(event.getItemDrop().getItemStack())) {
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
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !managed(player)) {
                return;
            }
            ItemStack cursor = player.getItemOnCursor();
            if (ItemFactory.isUi(cursor)) {
                player.setItemOnCursor(null);
            }
            carry.enforceContainers(player);
            carry.refreshLocks(player);
        });
    }
}
