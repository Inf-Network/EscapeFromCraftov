package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Secure container: contents survive death. Works in the lobby and in raids.
 *
 * <p>Room is counted in cells (3 at first, 9 fully upgraded, see TraderService) and an item takes
 * as many as its size in items.yml: a chip or a necklace one or two, a statue or a generator up to
 * six, ammo one per 30 rounds. Counting stacks instead let the smallest container keep three red
 * collectibles, so whatever big find went in at once was carried out at no risk. A chest window
 * cannot draw one icon across several slots, so an item shows in its first cell and its other
 * cells hold an "occupied" marker. A container that is over its cells (filled before items had
 * sizes) keeps everything, but nothing more goes in until it is back within them. The cell rules
 * live in CarryService ({@link CarryService#secureStore}), shared with the search screen's secure
 * cells.
 */
final class SecureMenu extends Menu {

    private final CraftovPlugin plugin;

    SecureMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 2, Text.mm("<text>安全箱 <muted>· 阵亡不掉落"));
        this.plugin = plugin;
    }

    private List<ItemRecord> contents() {
        return plugin.carry().secureContents(viewer);
    }

    private int capacity() {
        return plugin.carry().secureCapacity(viewer);
    }

    @Override
    protected void draw() {
        CarryService carry = plugin.carry();
        List<ItemRecord> items = contents();
        int cap = capacity();
        int used = carry.secureUsed(items);
        int[] layout = carry.secureLayout(items);
        boolean firstLocked = true;
        for (int slot = 0; slot < CarryService.SECURE_CELLS; slot++) {
            int index = layout[slot];
            if (index < 0) {
                if (slot >= cap) {
                    set(slot, UiItems.lockedCell(UiItems.Zone.SECURE, firstLocked, "安全箱", cap, CarryService.SECURE_CELLS));
                    firstLocked = false;
                }
                continue;
            }
            ItemRecord record = items.get(index);
            if (slot > 0 && layout[slot - 1] == index) {
                button(slot, UiItems.secureOccupied(plugin.items().registry().get(record.id), carry.secureCells(record)), e -> take(index));
                continue;
            }
            ItemStack stack = plugin.items().fromRecord(record);
            if (stack == null) {
                // An id items.yml no longer has: it cannot be built, but it can be moved to the mailbox.
                stack = UiItems.button("craftov:ui/collection_unknown", "<muted>未知物品 <white>" + Text.escape(record.id),
                        List.of("<muted>物品表里暂时没有这件物品", "<muted>点击移入仓库邮箱，恢复后可从仓库取回"));
            }
            button(slot, stack, e -> take(index));
        }
        fillRow(1, UiItems.filler());
        List<String> lore = new java.util.ArrayList<>(List.of("<muted>点击下方物品放入，点击上方物品取出",
                "<muted>物品按大小占格，大件一件占好几格", "<muted>弹药每格 30 发", "<muted>武器、护甲、胸挂与背包无法放入"));
        if (used > cap) {
            lore.add("<danger>已超出容量：取出物品降到 " + cap + " 格以内才能再放入");
        }
        set(13, UiItems.button("craftov:ui/icon_secure", (used > cap ? "<danger>" : "<info>") + "安全箱 " + used + "/" + cap + " 格", lore));
    }

    /**
     * False, with the reason said, when the container may not be used now. The menu can still be
     * open when that changes: a knock-down closes it, but the check must not rely on that alone.
     */
    private boolean usable() {
        String refused = plugin.menus().secureRefusal(viewer);
        if (refused == null) {
            return true;
        }
        plugin.menus().refuse(viewer, refused);
        return false;
    }

    private void take(int index) {
        List<ItemRecord> items = contents();
        if (index >= items.size() || !usable()) {
            return;
        }
        ItemStack stack = plugin.items().fromRecord(items.get(index));
        if (stack == null) {
            plugin.carry().mail(viewer, items.remove(index));
            changed();
            return;
        }
        ItemStack left = plugin.carry().give(viewer, stack, false);
        int moved = stack.getAmount() - (left == null ? 0 : left.getAmount());
        if (moved <= 0) {
            Notify.error(viewer, "携行空间不足");
            return;
        }
        // Only what was handed over leaves the record (one above the stack size builds a single stack).
        ItemRecord record = items.get(index);
        record.amount -= moved;
        if (record.amount <= 0) {
            items.remove(index);
        }
        changed();
    }

    @Override
    protected void onBottomClick(InventoryClickEvent event) {
        ItemStack stack = event.getCurrentItem();
        if (stack == null || stack.isEmpty() || ItemFactory.isUi(stack) || event.getClickedInventory() == null || !usable()) {
            return;
        }
        String why = plugin.carry().secureRefusal(stack);
        if (why != null) {
            Notify.error(viewer, why);
            return;
        }
        ItemDef def = plugin.items().defOf(stack);
        ItemRecord record = plugin.items().toRecord(stack);
        if (def == null || record == null) {
            return;
        }
        int moved = plugin.carry().secureStore(viewer, def, record);
        if (moved <= 0) {
            Notify.error(viewer, plugin.carry().secureFullReason(viewer, def));
            return;
        }
        if (moved >= stack.getAmount()) {
            event.getClickedInventory().setItem(event.getSlot(), null);
        } else {
            ItemStack rest = stack.clone();
            rest.setAmount(stack.getAmount() - moved);
            event.getClickedInventory().setItem(event.getSlot(), rest);
            Notify.info(viewer, "<accent>放入 " + moved + (def.category() == Category.AMMO ? " 发" : " 个") + "，安全箱已满");
        }
        changed();
    }

    private void changed() {
        Profile profile = plugin.profiles().get(viewer);
        if (plugin.raids().raidOf(viewer) == null) {
            plugin.lobby().captureLoadout(viewer);
        }
        plugin.profiles().markDirty(profile.uuid);
        viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", 0.6f, 1.1f);
        redraw();
    }
}
