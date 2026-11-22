package net.infnetwork.snowball.craftov.inventory;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.gui.UiItems;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.profile.ProfileService;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Maps the Delta-style carry layout onto the vanilla player inventory.
 * <pre>
 *  hotbar 0..7   pockets (always usable)      hotbar 8  terminal (locked)
 *  row 9..17     chest rig slots              rows 18..35 backpack slots
 *  36 feet = backpack   37 legs = rig   38 chest = body armor   39 head = helmet
 * </pre>
 * Rigs and backpacks carry their contents inside the item stack while unequipped. Whatever does
 * not fit anywhere is dropped at the player's feet in a raid and mailed to the stash mailbox
 * everywhere else, because outside a raid nobody can pick a dropped item up again.
 */
public final class CarryService {

    public static final int TERMINAL_SLOT = 8;
    /** Hotbar key 3 is reserved for the melee weapon; nothing else goes there and it is never lost. */
    public static final int MELEE_SLOT = 2;
    public static final int RIG_START = 9;
    public static final int RIG_MAX = 9;
    public static final int PACK_START = 18;
    public static final int PACK_MAX = 18;
    public static final int FEET = 36;
    public static final int LEGS = 37;
    public static final int CHEST = 38;
    public static final int HEAD = 39;
    public static final int OFFHAND = 40;

    private final CraftovPlugin plugin;
    private final ItemFactory items;

    public CarryService(CraftovPlugin plugin) {
        this.plugin = plugin;
        this.items = plugin.items();
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

    public boolean isMelee(@Nullable ItemStack stack) {
        ItemDef def = items.defOf(stack);
        return def != null && def.isMelee();
    }

    /** Puts the starter knife into an empty melee slot and moves anything that does not belong there. */
    public void ensureMelee(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack current = inv.getItem(MELEE_SLOT);
        if (isMelee(current)) {
            return;
        }
        inv.setItem(MELEE_SLOT, items.create("knife_starter", 1));
        if (current != null && !current.isEmpty() && !ItemFactory.isUi(current)) {
            give(player, current, true);
        }
    }

    public static boolean isRigSlot(int slot) {
        return slot >= RIG_START && slot < RIG_START + RIG_MAX;
    }

    public static boolean isPackSlot(int slot) {
        return slot >= PACK_START && slot < PACK_START + PACK_MAX;
    }

    /** Slot indices (0..35) the player can currently store items in. */
    public List<Integer> usableSlots(Player player) {
        return usableSlots(rigCapacity(player), backpackCapacity(player));
    }

    private static List<Integer> usableSlots(int rig, int pack) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < TERMINAL_SLOT; i++) {
            if (i != MELEE_SLOT) {
                out.add(i);
            }
        }
        for (int i = 0; i < rig; i++) {
            out.add(RIG_START + i);
        }
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
     * {@code dropOverflow} is set that remainder is spilled instead (see {@link #spill}).
     */
    public @Nullable ItemStack give(Player player, ItemStack stack, boolean dropOverflow) {
        ItemStack remaining = stack.clone();
        PlayerInventory inv = player.getInventory();
        if (isMelee(remaining)) {
            // A new melee weapon replaces the free starter knife; any other one stays where it is.
            ItemDef held = items.defOf(inv.getItem(MELEE_SLOT));
            if (held != null && "knife_starter".equals(held.id())) {
                inv.setItem(MELEE_SLOT, null);
            }
        }
        List<Integer> slots = isMelee(remaining) ? List.of(MELEE_SLOT) : usableSlots(player);
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
            spill(player, remaining);
            return null;
        }
        return remaining;
    }

    /** A copy of the player's inventory, indexed like {@link PlayerInventory#getItem(int)}, for {@link #simulateGive}. */
    public @Nullable ItemStack[] copyCells(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        @Nullable ItemStack[] out = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            out[i] = stack == null || stack.isEmpty() ? null : stack.clone();
        }
        return out;
    }

    /**
     * Dry run of {@link #give} on {@code cells} (from {@link #copyCells}): places {@code stack}
     * there the same way and returns false when some of it would be left over. Run it for each of
     * several stacks in turn so that each one sees the cells the ones before it took; checking
     * them one by one against the real inventory let two stacks count the same free cell. Cells
     * are counted from the rig and backpack in {@code cells}, and placeholders in them count as
     * free, so a container just put into its slot opens its cells as {@link #refreshLocks} would.
     */
    public boolean simulateGive(@Nullable ItemStack[] cells, ItemStack stack) {
        ItemStack remaining = stack.clone();
        if (ItemFactory.hasContents(remaining)) {
            return false;
        }
        List<Integer> slots;
        if (isMelee(remaining)) {
            ItemDef held = items.defOf(cells[MELEE_SLOT]);
            if (held != null && "knife_starter".equals(held.id())) {
                cells[MELEE_SLOT] = null;
            }
            slots = List.of(MELEE_SLOT);
        } else {
            slots = usableSlots(capacity(cells[LEGS], RIG_MAX), capacity(cells[FEET], PACK_MAX));
        }
        for (int slot : slots) {
            ItemStack current = cells[slot];
            if (current != null && !ItemFactory.isUi(current) && current.isSimilar(remaining)) {
                int space = current.getMaxStackSize() - current.getAmount();
                if (space > 0) {
                    int moved = Math.min(space, remaining.getAmount());
                    current.setAmount(current.getAmount() + moved);
                    remaining.setAmount(remaining.getAmount() - moved);
                    if (remaining.getAmount() <= 0) {
                        return true;
                    }
                }
            }
        }
        for (int slot : slots) {
            ItemStack current = cells[slot];
            if (current == null || current.isEmpty() || ItemFactory.isUi(current)) {
                cells[slot] = remaining;
                return true;
            }
        }
        return false;
    }

    /**
     * Gets rid of an item that has no room on the player. In a raid it drops at their feet for
     * anyone to loot. Anywhere else (lobby, range, editor) a dropped item can never be picked up
     * again, so it goes to the stash mailbox instead. Range loaners, proving-ground armory items and
     * non-Craftov stacks have no stored form and still drop.
     */
    public void spill(Player player, @Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty() || ItemFactory.isUi(stack)) {
            return;
        }
        if (plugin.raids().isRaidWorld(player.getWorld()) || !mail(player, stack)) {
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        }
    }

    /**
     * Settles the cursor on the way home from a raid (extraction, voided raid, server stop): back
     * into the carry cells, or straight to the stash mailbox when it does not fit, since give's own
     * overflow would drop it in the raid world being left. A proving-ground armory item stays on
     * the cursor for {@link net.infnetwork.snowball.craftov.lobby.LobbyService#stripTestItems} to
     * take away, but what the player packed into an armory rig or backpack is theirs and comes home
     * now, the same way. Putting the armory item back first would leave it packed on the cursor
     * whenever its slot is taken, and with no stored form it could not be mailed: it dropped in the
     * raid with the player's own items inside. A stack with no stored form at all (not a Craftov
     * item) still drops. Returns the records that went to the mailbox. Calling it again changes
     * nothing.
     */
    public List<ItemRecord> reclaimCursorHome(Player player) {
        List<ItemRecord> mailed = new ArrayList<>();
        ItemStack cursor = player.getItemOnCursor();
        if (ItemFactory.isTest(cursor)) {
            List<ItemRecord> armory = new ArrayList<>();
            List<ItemRecord> own = new ArrayList<>();
            for (ItemRecord child : items.contents(cursor)) {
                ItemRecord kept = ItemFactory.persistent(child);
                if (kept == null) {
                    armory.add(child);
                } else {
                    own.add(kept);
                }
            }
            if (!own.isEmpty()) {
                ItemStack shell = cursor.clone();
                items.setContents(shell, armory);
                player.setItemOnCursor(shell);
                for (ItemRecord record : own) {
                    ItemRecord sent = handBack(player, record);
                    if (sent != null) {
                        mailed.add(sent);
                    }
                }
            }
            return mailed;
        }
        ItemStack left = reclaimCursor(player);
        if (left != null) {
            ItemRecord record = items.toRecord(left);
            if (record == null) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            } else {
                mail(player, record);
                mailed.add(record);
            }
        }
        return mailed;
    }

    /**
     * Puts a record back into the carry cells, or into the stash mailbox when it does not fit or
     * items.yml no longer knows its id, never onto the floor (the player may still be in a raid
     * they are leaving). Returns what was mailed, or null.
     */
    public @Nullable ItemRecord handBack(Player player, ItemRecord record) {
        ItemStack stack = items.fromRecord(record);
        ItemRecord rest = stack == null ? record : items.toRecord(give(player, stack, false));
        if (rest != null) {
            mail(player, rest);
        }
        return rest;
    }

    /**
     * Puts the item held on the cursor (inventory screen open) back into the carry cells; a packed
     * rig or backpack is worn again if its slot is free. The cursor is empty afterwards. Returns what
     * did not fit, or null. The one way the cursor is settled: on closing the inventory and on
     * logging off (CarryListener), before an extraction is counted and on the way home from a raid
     * (both through {@link #reclaimCursorHome}), and before an explicit lobby loadout capture.
     * Calling it again finds the cursor empty.
     */
    public @Nullable ItemStack reclaimCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor.isEmpty()) {
            return null;
        }
        ItemStack moving = cursor.clone();
        player.setItemOnCursor(null);
        if (ItemFactory.isUi(moving)) {
            return null;
        }
        ItemDef def = items.defOf(moving);
        if (def != null && def.isContainer() && ItemFactory.hasContents(moving)) {
            ItemStack worn = player.getInventory().getItem(containerSlot(def.category()));
            if (worn == null || worn.isEmpty()) {
                equip(player, moving);
                return null;
            }
        }
        return give(player, moving, false);
    }

    /**
     * Stores a stack that has no room on the player in their stash mailbox (see
     * {@link #mail(Player, ItemRecord)}). Returns false, storing nothing, for a stack with no stored
     * form: range loaners, proving-ground armory items (those packed inside are left out) and
     * non-Craftov stacks, so an armory item can never turn into a real one this way.
     */
    public boolean mail(Player player, ItemStack stack) {
        ItemRecord record = items.toRecord(stack);
        if (record == null) {
            return false;
        }
        mail(player, record);
        return true;
    }

    /**
     * Keeps a record the player cannot hold right now in their stash mailbox. This is the one way an
     * online player's overflow reaches the mailbox. Items whose id items.yml no longer knows land here
     * too: they cannot be built, but the record is kept and can be taken out of the stash again once
     * the definition is back.
     */
    public void mail(Player player, ItemRecord packed) {
        // Proving-ground armory items never leave their raid, not even packed inside something.
        ItemRecord record = ItemFactory.persistent(packed);
        if (record == null) {
            return;
        }
        // Whatever reaches the mailbox has left its raid: an insurance mark kept there could match a
        // later raid and pay out an item nobody insured.
        record.uninsure();
        plugin.profiles().edit(player.getUniqueId(), () -> "no room on the player: add to mailbox " + ProfileService.json(record),
                p -> p.mailbox.add(record));
        ItemDef def = items.registry().get(record.id);
        String amount = record.amount > 1 ? " <#9AA0A6>×" + record.amount : "";
        Text.send(player, def == null
                ? "<#FFC23D>物品表里暂时没有 <white>" + Text.escape(record.id) + "</white>" + amount
                        + "<#FFC23D>，已存入仓库邮箱，恢复后可以从仓库取回。"
                : "<#9AA0A6>身上放不下，" + def.coloredName() + amount + " <#9AA0A6>已放进<#F2C94C>仓库邮箱<#9AA0A6>。");
    }

    public boolean hasRoomFor(Player player, ItemStack stack) {
        if (ItemFactory.hasContents(stack)) {
            return false;
        }
        PlayerInventory inv = player.getInventory();
        int need = stack.getAmount();
        for (int slot : isMelee(stack) ? List.of(MELEE_SLOT) : usableSlots(player)) {
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

    /** Non-empty rigs/backpacks that slipped into a carry cell get equipped or spilled. */
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
            spill(player, stack);
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
            // Proving-ground armory items stay test items inside the pack instead of disappearing.
            ItemRecord record = items.packRecord(cell);
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
                mail(player, record);
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

    /** Everything the player carries (excluding UI stacks and test items), keyed by inventory slot. */
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

    /**
     * Copies of the stacks the player carries, keyed by slot, for a player who disconnects mid-raid
     * and may rejoin it. Unlike records they keep every component (records keep the raid's insurance
     * mark too, but not the fire mode), and they keep proving-ground armory items as test items. Never store them as a loadout without
     * {@link #persistent}.
     */
    public Map<Integer, ItemStack> copyCarried(Player player) {
        Map<Integer, ItemStack> out = new HashMap<>();
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot <= HEAD; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack != null && !stack.isEmpty() && !ItemFactory.isUi(stack) && items.packRecord(stack) != null) {
                out.put(slot, stack.clone());
            }
        }
        return out;
    }

    /**
     * A {@link #copyCarried} snapshot as records without its test items, fit to be saved as the loadout
     * of a player who is offline. The raid is void for them, so its insurance marks are dropped (gear
     * that comes home is never insured). An armory rig or backpack is left out, so the player's own items in its cells
     * would land in locked cells and be dropped on the lobby floor at the next login: they move to free
     * pockets instead, and whatever does not fit is added to {@code homeless} for the caller to mail.
     */
    public Map<Integer, ItemRecord> persistent(Map<Integer, ItemStack> carried, List<ItemRecord> homeless) {
        boolean rigGone = ItemFactory.isTest(carried.get(LEGS));
        boolean packGone = ItemFactory.isTest(carried.get(FEET));
        Map<Integer, ItemRecord> out = new HashMap<>();
        List<ItemRecord> orphans = new ArrayList<>();
        carried.forEach((slot, stack) -> {
            // The record keeps the test marks of armory items packed inside, so they can be left out too.
            ItemRecord kept = ItemFactory.persistent(items.packRecord(stack));
            if (kept == null) {
                return;
            }
            kept.uninsure();
            if (rigGone && isRigSlot(slot) || packGone && isPackSlot(slot)) {
                orphans.add(kept);
            } else {
                out.put(slot, kept);
            }
        });
        for (ItemRecord orphan : orphans) {
            int free = -1;
            for (int slot = 0; slot < TERMINAL_SLOT && free < 0; slot++) {
                if (slot != MELEE_SLOT && !out.containsKey(slot)) {
                    free = slot;
                }
            }
            if (free < 0) {
                homeless.add(orphan);
            } else {
                out.put(free, orphan);
            }
        }
        return out;
    }

    public List<ItemRecord> allRecords(Player player) {
        return new ArrayList<>(snapshot(player).values());
    }

    /**
     * Rebuilds the inventory from a layout. Records whose id items.yml does not know are mailed and
     * taken out of the layout (the profile's loadout, say), so applying it again before the next
     * capture cannot mail them a second time.
     */
    public void apply(Player player, Map<Integer, ItemRecord> layout, ItemStack terminal) {
        Map<Integer, ItemStack> stacks = new HashMap<>();
        List<ItemRecord> unknown = new ArrayList<>();
        for (Iterator<Map.Entry<Integer, ItemRecord>> it = layout.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, ItemRecord> entry = it.next();
            ItemStack stack = items.fromRecord(entry.getValue());
            if (stack != null) {
                stacks.put(entry.getKey(), stack);
            } else {
                it.remove();
                unknown.add(entry.getValue());
            }
        }
        restore(player, stacks, terminal);
        for (ItemRecord record : unknown) {
            mail(player, record);
        }
    }

    /** Puts these stacks back in their slots; whatever no longer fits goes to free cells. */
    public void restore(Player player, Map<Integer, ItemStack> layout, ItemStack terminal) {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        player.setItemOnCursor(null);
        // Equipment first so capacity is known before cells are filled.
        for (int slot : new int[]{FEET, LEGS, CHEST, HEAD}) {
            ItemStack stack = layout.get(slot);
            if (stack != null) {
                inv.setItem(slot, stack.clone());
            }
        }
        inv.setItem(TERMINAL_SLOT, terminal);
        refreshLocks(player);
        List<ItemStack> overflow = new ArrayList<>();
        for (Map.Entry<Integer, ItemStack> entry : layout.entrySet()) {
            int slot = entry.getKey();
            if (slot >= FEET || slot == TERMINAL_SLOT) {
                continue;
            }
            ItemStack stack = entry.getValue().clone();
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

    /**
     * Everything the player carries at item value, the melee weapon included. Raid profit and the
     * free kit's asset check go by this: profit counts the knife at deploy and at extraction alike,
     * so one brought in cancels out and one found in the raid is profit.
     */
    public long carriedValue(Player player) {
        long total = 0;
        for (ItemRecord record : snapshot(player).values()) {
            total += items.value(record);
        }
        return total;
    }

    /**
     * Gear value (战备值): what the player carries, without the melee slot and without any melee
     * weapon. The difficulty gates, the deploy screen, the main menu, the HUD and the free kit's
     * carry limit go by this. The knife is never lost on death, so it puts nothing at stake: a
     * ◈95,000 karambit alone used to clear 机密's requirement.
     */
    public long gearValue(Player player) {
        long total = 0;
        for (Map.Entry<Integer, ItemRecord> entry : snapshot(player).entrySet()) {
            if (entry.getKey() != MELEE_SLOT) {
                total += items.gearValue(entry.getValue());
            }
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

    /** Removes up to {@code amount} of an item from carry cells (never test items); returns how many were removed. */
    public int takeItem(Player player, String id, int amount) {
        return takeItem(player, id, amount, false);
    }

    /** Like {@link #takeItem(Player, String, int)}, but from proving-ground armory stacks only when {@code test}. */
    public int takeItem(Player player, String id, int amount, boolean test) {
        PlayerInventory inv = player.getInventory();
        int removed = 0;
        for (int slot = 0; slot < 36 && removed < amount; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (id.equals(ItemFactory.idOf(stack)) && ItemFactory.isTest(stack) == test) {
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
