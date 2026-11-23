package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
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
import org.jspecify.annotations.Nullable;

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
 * sizes) keeps everything, but nothing more goes in until it is back within them.
 */
final class SecureMenu extends Menu {

    /** Slots in the top row: the most cells a container can have. */
    private static final int CELLS = 9;
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

    private @Nullable ItemDef def(ItemRecord record) {
        return plugin.items().registry().get(record.id);
    }

    /** Cells a stored record takes; one for an id items.yml no longer has. */
    private int cells(ItemRecord record) {
        ItemDef def = def(record);
        return def == null ? 1 : def.secureCells(record.amount);
    }

    private int used(List<ItemRecord> items) {
        int used = 0;
        for (ItemRecord record : items) {
            used += cells(record);
        }
        return used;
    }

    @Override
    protected void draw() {
        List<ItemRecord> items = contents();
        int cap = capacity();
        int used = used(items);
        // Cells that would run past the row only happen in a container over its capacity (at most
        // nine records, from when each took one slot): then every record gets one slot.
        boolean compact = used > CELLS;
        int slot = 0;
        for (int i = 0; i < items.size() && slot < CELLS; i++) {
            ItemRecord record = items.get(i);
            ItemStack stack = plugin.items().fromRecord(record);
            if (stack == null) {
                // An id items.yml no longer has: it cannot be built, but it can be moved to the mailbox.
                stack = UiItems.button("craftov:ui/collection_unknown", "<muted>未知物品 <white>" + Text.escape(record.id),
                        List.of("<muted>物品表里暂时没有这件物品", "<muted>点击移入仓库邮箱，恢复后可从仓库取回"));
            }
            int index = i;
            button(slot, stack, e -> take(index));
            int cells = compact ? 1 : cells(record);
            ItemDef def = def(record);
            for (int k = 1; k < cells && slot + k < CELLS; k++) {
                button(slot + k, occupied(def, cells), e -> take(index));
            }
            slot += cells;
        }
        for (; slot < CELLS; slot++) {
            if (slot >= cap) {
                set(slot, UiItems.locked("在商人处升级安全箱"));
            }
        }
        fillRow(1, UiItems.filler());
        List<String> lore = new java.util.ArrayList<>(List.of("<muted>点击下方物品放入，点击上方物品取出",
                "<muted>物品按大小占格，大件一件占好几格", "<muted>弹药每格 30 发", "<muted>武器、护甲、胸挂与背包无法放入"));
        if (used > cap) {
            lore.add("<danger>已超出容量：取出物品降到 " + cap + " 格以内才能再放入");
        }
        set(13, UiItems.button("craftov:ui/icon_secure", (used > cap ? "<danger>" : "<info>") + "安全箱 " + used + "/" + cap + " 格", lore));
    }

    /** What fills the other cells of an item that takes several: who holds them, and a click takes the item. */
    private static ItemStack occupied(@Nullable ItemDef def, int cells) {
        return UiItems.button("craftov:ui/secure_used", "<dim>占用 · " + (def == null ? "<muted>未知物品" : def.coloredName()),
                List.of("<muted>这件物品占 " + cells + " 格", "<muted>点击取出"));
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
        ItemDef def = plugin.items().defOf(stack);
        if (ItemFactory.isTest(stack)) {
            Notify.error(viewer, "试验场物资不能放入安全箱");
            return;
        }
        if (def == null || !def.fitsSecure()) {
            Notify.error(viewer, "该物品无法放入安全箱");
            return;
        }
        ItemRecord record = plugin.items().toRecord(stack);
        if (record == null) {
            return;
        }
        List<ItemRecord> items = contents();
        int cap = capacity();
        int used = used(items);
        String full = fullReason(def, cap, used);
        // Over capacity nothing goes in, not even into the room left in a cell already taken.
        int moved = used > cap ? 0 : put(items, def, record, cap - used);
        if (moved <= 0) {
            Notify.error(viewer, full);
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

    /**
     * Puts as much of {@code record} into {@code items} as fits: first into the room left in the
     * cells of a stack of the same kind already there (20 rounds and 10 more share a cell), then
     * into free cells, one record per {@link ItemDef#secureStack} so every cell shows what it
     * holds. Returns how many went in.
     */
    private int put(List<ItemRecord> items, ItemDef def, ItemRecord record, int free) {
        int left = record.amount;
        if (def.maxStack() > 1 && record.uid == null) {
            for (ItemRecord held : items) {
                if (left <= 0) {
                    break;
                }
                if (!sameKind(held, record)) {
                    continue;
                }
                int begun = Math.max(1, (held.amount + def.secureStack() - 1) / def.secureStack()) * def.secureStack();
                int room = Math.min(begun, def.maxStack()) - held.amount;
                if (room > 0) {
                    int n = Math.min(room, left);
                    held.amount += n;
                    left -= n;
                }
            }
        }
        while (left > 0 && free >= def.size()) {
            ItemRecord part = record.copy();
            part.amount = Math.min(left, def.secureStack());
            items.add(part);
            left -= part.amount;
            free -= def.size();
        }
        return record.amount - left;
    }

    /** True when two stored stacks are the same item (a free-kit stack is not the same as a bought one). */
    private boolean sameKind(ItemRecord a, ItemRecord b) {
        if (!a.id.equals(b.id) || a.uid != null || b.uid != null) {
            return false;
        }
        ItemRecord x = a.copy();
        x.amount = 1;
        ItemRecord y = b.copy();
        y.amount = 1;
        ItemStack built = plugin.items().fromRecord(x);
        return built != null && built.isSimilar(plugin.items().fromRecord(y));
    }

    /** Why nothing of an item fits: too big for the container, the container over capacity, or full. */
    private static String fullReason(ItemDef def, int cap, int used) {
        if (def.size() > cap) {
            return "这件物品占 " + def.size() + " 格，安全箱只有 " + cap + " 格";
        }
        if (used > cap) {
            return "安全箱超出容量（" + used + "/" + cap + " 格），先取出物品";
        }
        return "安全箱空间不足：需要 " + def.size() + " 格，剩余 " + (cap - used) + " 格";
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
