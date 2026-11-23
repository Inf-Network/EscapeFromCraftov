package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Secure container: contents survive death. Works in the lobby and in raids. */
final class SecureMenu extends Menu {

    private final CraftovPlugin plugin;

    SecureMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 2, Text.mm("<text>安全箱 <muted>· 阵亡不掉落"));
        this.plugin = plugin;
    }

    private List<ItemRecord> contents() {
        RaidPlayer rp = plugin.raids().session(viewer);
        if (rp != null && rp.active()) {
            return rp.secure;
        }
        return plugin.profiles().get(viewer).secure;
    }

    private int capacity() {
        return plugin.traders().secureSlots(plugin.profiles().get(viewer));
    }

    @Override
    protected void draw() {
        List<ItemRecord> items = contents();
        int cap = capacity();
        for (int slot = 0; slot < 9; slot++) {
            if (slot >= cap) {
                set(slot, UiItems.lockedCell(UiItems.Zone.SECURE, slot == cap, "安全箱", cap, 9));
                continue;
            }
            if (slot < items.size()) {
                ItemStack stack = plugin.items().fromRecord(items.get(slot));
                if (stack == null) {
                    // An id items.yml no longer has: it cannot be built, but it can be moved to the mailbox.
                    stack = UiItems.button("craftov:ui/collection_unknown", "<muted>未知物品 <white>" + Text.escape(items.get(slot).id),
                            List.of("<muted>物品表里暂时没有这件物品", "<muted>点击移入仓库邮箱，恢复后可从仓库取回"));
                }
                int index = slot;
                button(slot, stack, e -> take(index));
            }
        }
        fillRow(1, UiItems.filler());
        set(13, UiItems.button("craftov:ui/icon_secure", "<info>安全箱 " + items.size() + "/" + cap,
                List.of("<muted>点击下方物品放入，点击上方物品取出", "<muted>武器、护甲、胸挂与背包无法放入")));
    }

    private void take(int index) {
        List<ItemRecord> items = contents();
        if (index >= items.size()) {
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
        if (stack == null || stack.isEmpty() || ItemFactory.isUi(stack) || event.getClickedInventory() == null) {
            return;
        }
        String why = plugin.carry().secureRefusal(stack);
        if (why != null) {
            Notify.error(viewer, why);
            return;
        }
        List<ItemRecord> items = contents();
        if (items.size() >= capacity()) {
            Notify.error(viewer, "安全箱已满");
            return;
        }
        ItemRecord record = plugin.items().toRecord(stack);
        if (record == null) {
            return;
        }
        items.add(record);
        event.getClickedInventory().setItem(event.getSlot(), null);
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
