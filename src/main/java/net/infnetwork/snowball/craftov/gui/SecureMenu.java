package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
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
        super(viewer, 2, Text.mm("<#F2E6C8>安全箱 <#8A8F98>· 阵亡不掉落"));
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
                set(slot, UiItems.locked("在商人处升级安全箱"));
                continue;
            }
            if (slot < items.size()) {
                ItemStack stack = plugin.items().fromRecord(items.get(slot));
                if (stack != null) {
                    int index = slot;
                    button(slot, stack, e -> take(index));
                }
            }
        }
        fillRow(1, UiItems.filler());
        set(13, UiItems.button("craftov:ui/icon_secure", "<#B66BFF>安全箱 " + items.size() + "/" + cap,
                List.of("<#9AA0A6>点击下方物品放入，点击上方物品取出", "<#9AA0A6>武器、护甲、胸挂与背包无法放入")));
    }

    private void take(int index) {
        List<ItemRecord> items = contents();
        if (index >= items.size()) {
            return;
        }
        ItemStack stack = plugin.items().fromRecord(items.get(index));
        if (stack == null) {
            items.remove(index);
            redraw();
            return;
        }
        ItemStack left = plugin.carry().give(viewer, stack, false);
        if (left != null && left.getAmount() == stack.getAmount()) {
            viewer.sendActionBar(Text.mm("<#FF8A80>携行空间不足"));
            return;
        }
        if (left == null) {
            items.remove(index);
        } else {
            items.get(index).amount = left.getAmount();
        }
        changed();
    }

    @Override
    protected void onBottomClick(InventoryClickEvent event) {
        ItemStack stack = event.getCurrentItem();
        if (stack == null || stack.isEmpty() || ItemFactory.isUi(stack) || event.getClickedInventory() == null) {
            return;
        }
        ItemDef def = plugin.items().defOf(stack);
        if (def == null || def.isGun() || def.isArmor() || def.isContainer() || def.category() == Category.WEAPON) {
            viewer.sendActionBar(Text.mm("<#FF8A80>该物品无法放入安全箱"));
            return;
        }
        List<ItemRecord> items = contents();
        if (items.size() >= capacity()) {
            viewer.sendActionBar(Text.mm("<#FF8A80>安全箱已满"));
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
