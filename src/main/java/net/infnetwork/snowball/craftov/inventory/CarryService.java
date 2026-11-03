package net.infnetwork.snowball.craftov.inventory;

import net.infnetwork.snowball.craftov.gui.UiItems;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the Delta-style carry layout onto the vanilla player inventory.
 * <pre>
 *  hotbar 0..7   pockets (always usable)      hotbar 8  terminal (locked)
 *  row 9..17     chest rig slots              rows 18..35 backpack slots
 *  36 feet = backpack   37 legs = rig   38 chest = body armor   39 head = helmet
 * </pre>
 * Rigs and backpacks carry their contents inside the item stack while unequipped.
 */
public final class CarryService {

    public static final int TERMINAL_SLOT = 8;
    public static final int RIG_START = 9;
    public static final int RIG_MAX = 9;
    public static final int PACK_START = 18;
    public static final int PACK_MAX = 18;
    public static final int FEET = 36;
    public static final int LEGS = 37;
    public static final int CHEST = 38;
    public static final int HEAD = 39;
    public static final int OFFHAND = 40;

    private final ItemFactory items;

    public CarryService(ItemFactory items) {
        this.items = items;
    }

    public ItemFactory items() {
        return items;
    }

    public int rigCapacity(Player player) {
        return capacity(player.getInventory().getItem(LEGS), RIG_MAX);
    }

    public int backpackCapacity(Player player) {
        return capacity(player.getInventory().getItem(FEET), PACK_MAX);
    }

    private int capacity(@Nullable ItemStack stack, int max) {
        ItemDef def = items.defOf(stack);
        return def == null || !def.isContainer() ? 0 : Math.min(max, def.slots());
    }

    public static boolean isRigSlot(int slot) {
        return slot >= RIG_START && slot < RIG_START + RIG_MAX;
    }

    public static boolean isPackSlot(int slot) {
        return slot >= PACK_START && slot < PACK_START + PACK_MAX;
    }

    /** Slot indices (0..35) the player can currently store items in. */
    public List<Integer> usableSlots(Player player) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < TERMINAL_SLOT; i++) {
            out.add(i);
        }
        int rig = rigCapacity(player);
        for (int i = 0; i < rig; i++) {
            out.add(RIG_START + i);
        }
        int pack = backpackCapacity(player);
        for (int i = 0; i < pack; i++) {
            out.add(PACK_START + i);
        }
        return out;
    }

    /** Places locked placeholders on unusable slots and moves stray items to free cells. */
    public void refreshLocks(Player player) {
        PlayerInventory inv = player.getInventory();
        int rig = rigCapacity(player);
        int pack = backpackCapacity(player);
        List<ItemStack> strays = new ArrayList<>();
        for (int i = 0; i < RIG_MAX; i++) {
            fixSlot(inv, RIG_START + i, i < rig, "装备胸挂解锁此格", strays);
        }
        for (int i = 0; i < PACK_MAX; i++) {
            fixSlot(inv, PACK_START + i, i < pack, "装备背包解锁此格", strays);
        }
        ItemStack off = inv.getItem(OFFHAND);
        if (off != null && !off.isEmpty()) {
            inv.setItem(OFFHAND, null);
            if (!UiItems.LOCKED.equals(ItemFactory.uiTag(off))) {
                strays.add(off);
            }
        }
        for (ItemStack stray : strays) {
            if (!ItemFactory.isUi(stray)) {
                give(player, stray, true);
            }
        }
    }

    private void fixSlot(PlayerInventory inv, int slot, boolean usable, String reason, List<ItemStack> strays) {
        ItemStack current = inv.getItem(slot);
        boolean locked = UiItems.LOCKED.equals(ItemFactory.uiTag(current));
        if (usable) {
            if (locked) {
                inv.setItem(slot, null);
            }
        } else if (!locked) {
            if (current != null && !current.isEmpty()) {
                strays.add(current);
            }
            inv.setItem(slot, UiItems.locked(reason));
        }
    }

    /**
     * Adds an item to free usable slots, stacking first. Returns the part that did not fit; when
     * {@code dropOverflow} is set that remainder is dropped at the player's feet instead.
     */
    public @Nullable ItemStack give(Player player, ItemStack stack, boolean dropOverflow) {
        ItemStack remaining = stack.clone();
        PlayerInventory inv = player.getInventory();
        List<Integer> slots = usableSlots(player);
        boolean containerWithContents = ItemFactory.hasContents(remaining);
        if (!containerWithContents) {
            for (int slot : slots) {
                ItemStack current = inv.getItem(slot);
                if (current != null && !current.isEmpty() && current.isSimilar(remaining)) {
                    int space = current.getMaxStackSize() - current.getAmount();
                    if (space > 0) {
                        int moved = Math.min(space, remaining.getAmount());
                        current.setAmount(current.getAmount() + moved);
                        remaining.setAmount(remaining.getAmount() - moved);
                        if (remaining.getAmount() <= 0) {
                            return null;
                        }
                    }
                }
            }
            for (int slot : slots) {
                ItemStack current = inv.getItem(slot);
                if (current == null || current.isEmpty()) {
                    inv.setItem(slot, remaining);
                    return null;
                }
            }
        }
        if (dropOverflow) {
            player.getWorld().dropItemNaturally(player.getLocation(), remaining);
            return null;
        }
        return remaining;
    }

    public boolean hasRoomFor(Player player, ItemStack stack) {
        if (ItemFactory.hasContents(stack)) {
            return false;
        }
        PlayerInventory inv = player.getInventory();
        int need = stack.getAmount();
        for (int slot : usableSlots(player)) {
            ItemStack current = inv.getItem(slot);
            if (current == null || current.isEmpty()) {
                return true;
            }
            if (current.isSimilar(stack)) {
                need -= current.getMaxStackSize() - current.getAmount();
                if (need <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Non-empty rigs/backpacks that slipped into a carry cell get equipped or dropped. */
    public void enforceContainers(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < FEET; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!ItemFactory.hasContents(stack)) {
                continue;
            }
            ItemDef def = items.defOf(stack);
            inv.setItem(slot, null);
            if (def != null && def.isContainer()) {
                ItemStack equipped = inv.getItem(containerSlot(def.category()));
                if (equipped == null || equipped.isEmpty()) {
                    equip(player, stack);
                    continue;
                }
            }
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        }
    }

    // ------------------------------------------------------------------ rig / backpack packing

    public static int containerSlot(Category category) {
        return category == Category.RIG ? LEGS : FEET;
    }

    /** Removes the equipped container in {@code armorSlot}, packing its cells into the stack. */
    public @Nullable ItemStack unequip(Player player, int armorSlot) {
        PlayerInventory inv = player.getInventory();
        ItemStack equipped = inv.getItem(armorSlot);
        if (equipped == null || equipped.isEmpty()) {
            return null;
        }
        int start = armorSlot == LEGS ? RIG_START : PACK_START;
        int cap = capacity(equipped, armorSlot == LEGS ? RIG_MAX : PACK_MAX);
        List<ItemRecord> packed = new ArrayList<>();
        for (int i = 0; i < cap; i++) {
            ItemStack cell = inv.getItem(start + i);
            if (cell == null || cell.isEmpty() || ItemFactory.isUi(cell)) {
                continue;
            }
            ItemRecord record = items.toRecord(cell);
            if (record != null) {
                packed.add(record);
            }
            inv.setItem(start + i, null);
        }
        ItemStack result = equipped.clone();
        items.setContents(result, packed);
        inv.setItem(armorSlot, null);
        refreshLocks(player);
        return result;
    }

    /** Equips a container, unpacking its stored contents into the matching cells. */
    public void equip(Player player, ItemStack container) {
        ItemDef def = items.defOf(container);
        if (def == null || !def.isContainer()) {
            return;
        }
        int armorSlot = containerSlot(def.category());
        PlayerInventory inv = player.getInventory();
        List<ItemRecord> contents = items.contents(container);
        ItemStack clean = container.clone();
        items.setContents(clean, List.of());
        inv.setItem(armorSlot, clean);
        refreshLocks(player);
        int start = armorSlot == LEGS ? RIG_START : PACK_START;
        int cap = Math.min(def.slots(), armorSlot == LEGS ? RIG_MAX : PACK_MAX);
        int cursor = 0;
        for (ItemRecord record : contents) {
            ItemStack stack = items.fromRecord(record);
            if (stack == null) {
                continue;
            }
            while (cursor < cap && inv.getItem(start + cursor) != null && !inv.getItem(start + cursor).isEmpty()) {
                cursor++;
            }
            if (cursor < cap) {
                inv.setItem(start + cursor, stack);
                cursor++;
            } else {
                give(player, stack, true);
            }
        }
    }

    // ------------------------------------------------------------------ snapshots

    /** Everything the player carries (excluding UI stacks), keyed by inventory slot. */
    public Map<Integer, ItemRecord> snapshot(Player player) {
        Map<Integer, ItemRecord> out = new HashMap<>();
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot <= HEAD; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.isEmpty() || ItemFactory.isUi(stack)) {
                continue;
            }
            ItemRecord record = items.toRecord(stack);
            if (record != null) {
                out.put(slot, record);
            }
        }
        return out;
    }

    public List<ItemRecord> allRecords(Player player) {
        return new ArrayList<>(snapshot(player).values());
    }

    public void apply(Player player, Map<Integer, ItemRecord> layout, ItemStack terminal) {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        player.setItemOnCursor(null);
        // Equipment first so capacity is known before cells are filled.
        for (int slot : new int[]{FEET, LEGS, CHEST, HEAD}) {
            ItemRecord record = layout.get(slot);
            if (record != null) {
                inv.setItem(slot, items.fromRecord(record));
            }
        }
        inv.setItem(TERMINAL_SLOT, terminal);
        refreshLocks(player);
        List<ItemStack> overflow = new ArrayList<>();
        for (Map.Entry<Integer, ItemRecord> entry : layout.entrySet()) {
            int slot = entry.getKey();
            if (slot >= FEET || slot == TERMINAL_SLOT) {
                continue;
            }
            ItemStack stack = items.fromRecord(entry.getValue());
            if (stack == null) {
                continue;
            }
            ItemStack current = inv.getItem(slot);
            if (slot < 36 && (current == null || current.isEmpty())) {
                inv.setItem(slot, stack);
            } else {
                overflow.add(stack);
            }
        }
        for (ItemStack stack : overflow) {
            give(player, stack, true);
        }
    }

    public long carriedValue(Player player) {
        long total = 0;
        for (ItemRecord record : snapshot(player).values()) {
            total += items.value(record);
        }
        return total;
    }

    public int countItem(Player player, String id) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (id.equals(ItemFactory.idOf(stack))) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** Removes up to {@code amount} of an item from carry cells; returns how many were removed. */
    public int takeItem(Player player, String id, int amount) {
        PlayerInventory inv = player.getInventory();
        int removed = 0;
        for (int slot = 0; slot < 36 && removed < amount; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (id.equals(ItemFactory.idOf(stack))) {
                int take = Math.min(amount - removed, stack.getAmount());
                stack.setAmount(stack.getAmount() - take);
                removed += take;
                if (stack.getAmount() <= 0) {
                    inv.setItem(slot, null);
                }
            }
        }
        return removed;
    }

    public static EquipmentSlot equipmentSlot(int slot) {
        return switch (slot) {
            case FEET -> EquipmentSlot.FEET;
            case LEGS -> EquipmentSlot.LEGS;
            case CHEST -> EquipmentSlot.CHEST;
            case HEAD -> EquipmentSlot.HEAD;
            default -> EquipmentSlot.HAND;
        };
    }
}
