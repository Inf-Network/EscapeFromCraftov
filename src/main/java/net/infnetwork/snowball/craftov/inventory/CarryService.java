package net.infnetwork.snowball.craftov.inventory;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.gui.UiItems;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.profile.ProfileService;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.GameMode;
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
 *  hotbar 0..1   guns (keys 1-2)               hotbar 2  melee weapon (key 3)
 *  hotbar 3..7   pockets                       hotbar 8  terminal (locked)
 *  row 9..17     chest rig slots              rows 18..35 backpack slots
 *  36 feet = backpack   37 legs = rig   38 chest = body armor   39 head = helmet
 * </pre>
 * Each zone takes only what belongs there ({@link #refusal}): keys 1-2 take guns, and the pockets
 * (keys 4-8) and the rig take anything small: no guns, armour, rigs or backpacks, which are worn
 * or go into the backpack. Loot goes where it belongs ({@link #route}): ammo, meds and grenades to
 * the free pockets first, then the rig, then the backpack; guns to a free gun key, then the
 * backpack; everything else to the backpack, then the rig, then the pockets, so a raider with
 * neither rig nor backpack can still carry what a crate holds (#176).
 * Rigs and backpacks carry their contents inside the item stack while unequipped. Whatever does
 * not fit anywhere is dropped at the player's feet in a raid and mailed to the stash mailbox
 * everywhere else, because outside a raid nobody can pick a dropped item up again.
 */
public final class CarryService {

    public static final int TERMINAL_SLOT = 8;
    /** Hotbar key 3 is reserved for the melee weapon; nothing else goes there and it is never lost. */
    public static final int MELEE_SLOT = 2;
    /** Hotbar keys 1-2: guns only. */
    public static final List<Integer> GUN_KEYS = List.of(0, 1);
    /** Hotbar keys 4-8, the pockets: anything that is not a gun, armour or a rig or backpack (#176). */
    public static final List<Integer> QUICK_KEYS = List.of(3, 4, 5, 6, 7);
    public static final int RIG_START = 9;
    public static final int RIG_MAX = 9;
    public static final int PACK_START = 18;
    public static final int PACK_MAX = 18;
    public static final int FEET = 36;
    public static final int LEGS = 37;
    public static final int CHEST = 38;
    public static final int HEAD = 39;
    public static final int OFFHAND = 40;
    /** Cells of a fully upgraded secure container: the most any menu shows. */
    public static final int SECURE_CELLS = 9;

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

    // ------------------------------------------------------------------ zone rules (#10)

    /** What a fight reaches for: these go to the pockets (keys 4-8) first. */
    private static boolean quick(Category category) {
        return category == Category.AMMO || category == Category.MEDICAL || category == Category.THROWABLE;
    }

    /** Guns, armour and rigs or backpacks are too big for a pocket or a rig pouch. */
    private static boolean bulky(Category category) {
        return category == Category.WEAPON || category.isGear();
    }

    /**
     * Why {@code stack} may not be put into carry cell {@code slot} (0..35), or null when it may. Keys
     * 1-2 take guns only, key 3 the melee weapon only, key 9 nothing (the terminal), the pockets (keys
     * 4-8) and the rig no guns, armour, rigs or backpacks; the backpack takes anything
     * but a melee weapon. A stack that is not a Craftov item goes only where anything goes.
     */
    public @Nullable String refusal(@Nullable ItemStack stack, int slot) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (ItemFactory.isUi(stack)) {
            return "这里放不下界面物品";
        }
        ItemDef def = items.defOf(stack);
        if (def != null && def.isMelee()) {
            return slot == MELEE_SLOT ? null : "近战武器只能放在 3 号键";
        }
        if (slot == MELEE_SLOT) {
            return "3 号键只放近战武器";
        }
        if (slot == TERMINAL_SLOT) {
            return "9 号键是战术终端";
        }
        Category category = def == null ? null : def.category();
        if (GUN_KEYS.contains(slot)) {
            return category == Category.WEAPON ? null : "1、2 号键只放枪械";
        }
        if (QUICK_KEYS.contains(slot) && category != null && bulky(category)) {
            return "口袋（4–8 号键）放不下" + category.displayName()
                    + (category == Category.WEAPON ? "，枪放在 1、2 号键或背包" : "，请穿上或放进背包");
        }
        if (isRigSlot(slot) && category != null && bulky(category)) {
            return "胸挂放不下" + category.displayName() + "，请放进背包";
        }
        return null;
    }

    /**
     * Why a click on the player's own carry cell {@code slot} (0..35) breaks the zone rules, or null:
     * placing or swapping in the cursor, or a number-key swap, either way. Taking things out is
     * always fine. Equipment slots (36 and up) have rules of their own.
     */
    public @Nullable String clickRefusal(Player player, org.bukkit.event.inventory.InventoryAction action, int slot,
                                         @Nullable ItemStack current, @Nullable ItemStack cursor, int hotbarButton) {
        if (slot < 0 || slot >= FEET) {
            return null;
        }
        return switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> refusal(cursor, slot);
            case HOTBAR_SWAP -> {
                if (hotbarButton < 0 || hotbarButton > 8) {
                    yield null;
                }
                String in = refusal(player.getInventory().getItem(hotbarButton), slot);
                yield in != null ? in : refusal(current, hotbarButton);
            }
            default -> null;
        };
    }

    /**
     * The cells (0..35) that {@code stack} is put into, in order, for a player with this much rig and
     * backpack: ammo, meds and grenades to the pockets, then the rig, then the backpack; guns to the
     * gun keys, then the backpack; armour, rigs and backpacks to the backpack only; anything else
     * (keys too) to the backpack, then the rig, then the pockets. Every cell listed accepts the stack ({@link #refusal}). With
     * {@code avoid} >= 0 that cell is left out: loot never lands in the slot the player holds, or the
     * gun in their hands would turn into whatever they just picked up.
     */
    public List<Integer> route(@Nullable ItemStack stack, int rig, int pack, int avoid) {
        ItemDef def = items.defOf(stack);
        if (def != null && def.isMelee()) {
            return List.of(MELEE_SLOT);
        }
        Category category = def == null ? null : def.category();
        List<Integer> rigCells = new ArrayList<>();
        for (int i = 0; i < rig; i++) {
            rigCells.add(RIG_START + i);
        }
        List<Integer> packCells = new ArrayList<>();
        for (int i = 0; i < pack; i++) {
            packCells.add(PACK_START + i);
        }
        List<Integer> out = new ArrayList<>();
        if (category == Category.WEAPON) {
            out.addAll(GUN_KEYS);
            out.addAll(packCells);
        } else if (category != null && quick(category)) {
            out.addAll(QUICK_KEYS);
            out.addAll(rigCells);
            out.addAll(packCells);
        } else if (category != null && bulky(category)) {
            out.addAll(packCells);
        } else {
            out.addAll(packCells);
            out.addAll(rigCells);
            out.addAll(QUICK_KEYS);
        }
        out.remove(Integer.valueOf(avoid));
        return out;
    }

    /**
     * {@link #route} for what the player wears now. Loot stays out of the held slot, except a gun
     * when that slot is an empty gun key: nothing in hand turns into it, and with the other gun key
     * taken and no backpack cell free, a gun just dropped from the hand could otherwise not be
     * picked up again until the player switched slots (#180). It is tried last there.
     */
    public List<Integer> route(Player player, @Nullable ItemStack stack, boolean looting) {
        int held = player.getInventory().getHeldItemSlot();
        List<Integer> out = route(stack, rigCapacity(player), backpackCapacity(player), looting ? held : -1);
        ItemStack inHand = player.getInventory().getItem(held);
        ItemDef def = items.defOf(stack);
        if (looting && GUN_KEYS.contains(held) && (inHand == null || inHand.isEmpty())
                && def != null && def.category() == Category.WEAPON) {
            out = new ArrayList<>(out);
            out.add(held);
        }
        return out;
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

    /**
     * Places locked placeholders on unusable slots and moves stray items to free cells. A locked cell
     * is striped in its zone's colour (UiItems.lockedCell); only the first one of each zone has a
     * tooltip, which gives the zone's capacity, so the hover over a row of them does not flicker.
     */
    public void refreshLocks(Player player) {
        PlayerInventory inv = player.getInventory();
        int rig = rigCapacity(player);
        int pack = backpackCapacity(player);
        List<ItemStack> strays = new ArrayList<>();
        ItemDef rigDef = items.defOf(inv.getItem(LEGS));
        ItemDef packDef = items.defOf(inv.getItem(FEET));
        for (int i = 0; i < RIG_MAX; i++) {
            fixSlot(inv, RIG_START + i, i < rig, UiItems.lockedCell(UiItems.Zone.RIG, i == rig,
                    rigDef == null ? null : rigDef.name(), rig, RIG_MAX), strays);
        }
        for (int i = 0; i < PACK_MAX; i++) {
            fixSlot(inv, PACK_START + i, i < pack, UiItems.lockedCell(UiItems.Zone.PACK, i == pack,
                    packDef == null ? null : packDef.name(), pack, PACK_MAX), strays);
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

    private void fixSlot(PlayerInventory inv, int slot, boolean usable, ItemStack lock, List<ItemStack> strays) {
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
            inv.setItem(slot, lock);
        } else if (!lock.isSimilar(current)) {
            // Still locked, but the zone's capacity (in the first cell's tooltip) or its look changed.
            inv.setItem(slot, lock);
        }
    }

    /**
     * Adds an item to free usable slots, stacking first. Returns the part that did not fit; when
     * {@code dropOverflow} is set that remainder is spilled instead (see {@link #spill}).
     */
    public @Nullable ItemStack give(Player player, ItemStack stack, boolean dropOverflow) {
        return give(player, stack, dropOverflow, false);
    }

    /** The worn slot this helmet, vest, rig or backpack goes on, or -1 for anything else. */
    public int wearSlot(@Nullable ItemStack stack) {
        ItemDef def = items.defOf(stack);
        if (def == null) {
            return -1;
        }
        if (def.isContainer()) {
            return containerSlot(def.category());
        }
        return def.isArmor() ? def.category() == Category.HELMET ? HEAD : CHEST : -1;
    }

    /**
     * Taking something (out of a box, the stash, the trader): a helmet, vest, rig or backpack goes on
     * when nothing is worn there, since none of them fits anywhere but the backpack; anything else,
     * or gear whose slot is taken, goes the way of {@link #give(Player, ItemStack, boolean, boolean)}.
     */
    public @Nullable ItemStack giveOrWear(Player player, ItemStack stack, boolean dropOverflow, boolean looting) {
        int slot = wearSlot(stack);
        ItemStack worn = slot < 0 ? null : player.getInventory().getItem(slot);
        if (slot >= 0 && (worn == null || worn.isEmpty())) {
            if (slot == LEGS || slot == FEET) {
                equip(player, stack.clone());
            } else {
                player.getInventory().setItem(slot, stack.clone());
            }
            return null;
        }
        return give(player, stack, dropOverflow, looting);
    }

    /**
     * {@link #give(Player, ItemStack, boolean)} along the item's {@link #route}. With {@code looting}
     * (a search screen, a pickup from the ground) the slot the player holds is left out.
     */
    public @Nullable ItemStack give(Player player, ItemStack stack, boolean dropOverflow, boolean looting) {
        ItemStack remaining = stack.clone();
        PlayerInventory inv = player.getInventory();
        if (isMelee(remaining)) {
            // A new melee weapon replaces the free starter knife; any other one stays where it is.
            ItemDef held = items.defOf(inv.getItem(MELEE_SLOT));
            if (held != null && "knife_starter".equals(held.id())) {
                inv.setItem(MELEE_SLOT, null);
            }
        }
        return place(player, remaining, route(player, remaining, looting), dropOverflow);
    }

    /**
     * {@link #give(Player, ItemStack, boolean)} with the rig's cells tried before the backpack's.
     * The range rack hands out attachments this way: guns fit nowhere but the gun keys and the
     * backpack, so attachments that filled the backpack first left guns without a cell while the
     * rig stood empty, and whether everything on the rack fitted depended on the order it was taken.
     */
    public @Nullable ItemStack giveRigFirst(Player player, ItemStack stack) {
        List<Integer> route = route(player, stack, false);
        List<Integer> rig = route.stream().filter(CarryService::isRigSlot).toList();
        List<Integer> order = new ArrayList<>();
        for (int slot : route) {
            if (isRigSlot(slot)) {
                continue;
            }
            if (isPackSlot(slot) && !order.containsAll(rig)) {
                order.addAll(rig);
            }
            order.add(slot);
        }
        if (!order.containsAll(rig)) {
            order.addAll(rig);
        }
        return place(player, stack.clone(), order, false);
    }

    /** Stacks {@code remaining} onto these cells, then puts it into the first empty one; see {@link #give}. */
    private @Nullable ItemStack place(Player player, ItemStack remaining, List<Integer> slots, boolean dropOverflow) {
        PlayerInventory inv = player.getInventory();
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
        return simulate(cells, stack, false);
    }

    /**
     * Dry run of {@link #giveOrWear}: like {@link #simulateGive}, but a helmet, vest, rig or backpack
     * whose slot is empty in {@code cells} is put on there first, which also opens a container's cells
     * for the stacks after it.
     */
    public boolean simulateGiveOrWear(@Nullable ItemStack[] cells, ItemStack stack) {
        return simulate(cells, stack, true);
    }

    private boolean simulate(@Nullable ItemStack[] cells, ItemStack stack, boolean wear) {
        int wornSlot = wear ? wearSlot(stack) : -1;
        if (wornSlot >= 0 && (cells[wornSlot] == null || cells[wornSlot].isEmpty())) {
            cells[wornSlot] = stack.clone();
            return true;
        }
        ItemStack remaining = stack.clone();
        if (ItemFactory.hasContents(remaining)) {
            return false;
        }
        if (isMelee(remaining)) {
            ItemDef held = items.defOf(cells[MELEE_SLOT]);
            if (held != null && "knife_starter".equals(held.id())) {
                cells[MELEE_SLOT] = null;
            }
        }
        List<Integer> slots = route(remaining, capacity(cells[LEGS], RIG_MAX), capacity(cells[FEET], PACK_MAX), -1);
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
     * Settles the cursor on the way home from a raid (extraction, leaving the raid world): back
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

    /** Whether the carry rules apply to the player: never in creative or spectator mode. */
    public static boolean manages(Player player) {
        return player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR;
    }

    /**
     * Puts the cursor back on the player the way closing the inventory screen does. Right after the
     * close event vanilla puts it into the first free cell, or, with none free, throws it. That throw
     * is refused in the lobby, on the range and while downed, and for a gun even by a raider who is
     * up (a throw that does not come from a click is a reload), and a refused throw is put back with
     * addItem, which deletes whatever has no room. A packed rig or backpack must not land in a cell
     * either. So the cursor is always settled here first ({@link #reclaimCursor}): a helmet, vest,
     * rig or backpack goes back on when its slot is free, anything else finds its carry cell, and
     * what has no room is spilled (mailed outside a raid, dropped at the player's feet in one). Players
     * in creative or spectator mode are left alone ({@link #manages}). Used on closing the
     * inventory, which a deploy does before it records anything, and on logging off (CarryListener),
     * and for everyone when the server stops, which removes the players only once the plugin is
     * disabled, so neither of those runs then.
     */
    public void settleCursor(Player player) {
        if (manages(player)) {
            spill(player, reclaimCursor(player));
        }
    }

    /**
     * Puts the item held on the cursor (inventory screen open) back into the carry cells; a helmet,
     * vest, rig or backpack, packed or not, is worn again if its slot is free ({@link #giveOrWear}).
     * Gear fits no carry cell but the backpack's, so the helmet just taken off with the backpack full,
     * or the empty backpack just taken off, would otherwise have nowhere to go and be mailed or
     * dropped. The cursor is empty afterwards. Returns what did not fit, or null. The one way the
     * cursor is settled: on closing the inventory, on logging off and when the server stops (all
     * three through {@link #settleCursor}), before an extraction is counted and on the way home from
     * a raid (both through {@link #reclaimCursorHome}), and before an explicit lobby loadout capture.
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
        return giveOrWear(player, moving, false, false);
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
                p -> {
                    p.mailbox.add(record);
                    if (p.deployment != null) {
                        // Sent from a raid: it comes out of the mailbox again should the raid be void.
                        p.deployment.mailed.add(record.copy());
                    }
                });
        ItemDef def = items.registry().get(record.id);
        String amount = record.amount > 1 ? " <muted>×" + record.amount : "";
        Text.send(player, def == null
                ? "<accent>物品表里暂时没有 <white>" + Text.escape(record.id) + "</white>" + amount
                        + "<accent>，已存入仓库邮箱，恢复后可以从仓库取回。"
                : "<muted>身上放不下，" + def.coloredName() + amount + " <muted>已放进<accent>仓库邮箱<muted>。");
    }

    /** Whether {@link #giveOrWear} would place all of {@code stack} (loot: never into the held slot). */
    public boolean hasRoomFor(Player player, ItemStack stack, boolean looting) {
        PlayerInventory inv = player.getInventory();
        int wear = wearSlot(stack);
        if (wear >= 0 && (inv.getItem(wear) == null || inv.getItem(wear).isEmpty())) {
            return true;
        }
        if (ItemFactory.hasContents(stack)) {
            return false;
        }
        int need = stack.getAmount();
        for (int slot : route(player, stack, looting)) {
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

    /**
     * Tidies the rig and the backpack (the inventory's tidy cell, the loot screen's tidy button): stacks
     * of the same thing merge, and everything is put back along its {@link #route} sorted by category,
     * then rarity, then value, so ammo and meds fill the rig and the rest the backpack. The hotbar is
     * left as the player set it. Returns how many cells were freed by merging.
     */
    public int tidy(Player player) {
        plugin.weapons().flush(player);
        PlayerInventory inv = player.getInventory();
        int rig = rigCapacity(player);
        int pack = backpackCapacity(player);
        List<Integer> cells = new ArrayList<>();
        for (int i = 0; i < rig; i++) {
            cells.add(RIG_START + i);
        }
        for (int i = 0; i < pack; i++) {
            cells.add(PACK_START + i);
        }
        List<ItemStack> taken = new ArrayList<>();
        for (int slot : cells) {
            ItemStack stack = inv.getItem(slot);
            if (stack != null && !stack.isEmpty() && !ItemFactory.isUi(stack)) {
                taken.add(stack.clone());
                inv.setItem(slot, null);
            }
        }
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack stack : taken) {
            for (ItemStack into : merged) {
                if (stack.getAmount() > 0 && into.isSimilar(stack) && into.getAmount() < into.getMaxStackSize()) {
                    int moved = Math.min(into.getMaxStackSize() - into.getAmount(), stack.getAmount());
                    into.setAmount(into.getAmount() + moved);
                    stack.setAmount(stack.getAmount() - moved);
                }
            }
            if (stack.getAmount() > 0) {
                merged.add(stack);
            }
        }
        merged.sort(java.util.Comparator.<ItemStack>comparingInt(s -> {
            ItemDef d = items.defOf(s);
            return d == null ? 99 : d.category().ordinal();
        }).thenComparingInt(s -> {
            ItemDef d = items.defOf(s);
            return d == null ? 0 : -d.rarity().ordinal();
        }).thenComparingLong(s -> -items.value(s)));
        List<ItemStack> unplaced = new ArrayList<>();
        for (ItemStack stack : merged) {
            int target = -1;
            for (int slot : route(stack, rig, pack, -1)) {
                ItemStack there = inv.getItem(slot);
                if (cells.contains(slot) && (there == null || there.isEmpty())) {
                    target = slot;
                    break;
                }
            }
            if (target < 0) {
                unplaced.add(stack);
            } else {
                inv.setItem(target, stack);
            }
        }
        // Something the rules keep out of every free cell (a gun found in a rig pouch from before the
        // rules, with the backpack full): it stays in the bag somewhere rather than leaving it.
        for (ItemStack stack : unplaced) {
            for (int slot : cells) {
                ItemStack there = inv.getItem(slot);
                if (there == null || there.isEmpty()) {
                    inv.setItem(slot, stack);
                    stack = null;
                    break;
                }
            }
            if (stack != null) {
                give(player, stack, true);
            }
        }
        return taken.size() - merged.size();
    }

    /**
     * Shift-click on a carry cell in the inventory screen: the stack moves to the other side, hotbar to
     * rig and backpack or back to a hotbar key that takes it, and to the other carry zone when that is
     * all there is. Vanilla would drop it into the first free cell whatever the zone rules say.
     */
    public void moveAcross(Player player, int slot) {
        PlayerInventory inv = player.getInventory();
        ItemStack stack = inv.getItem(slot);
        if (stack == null || stack.isEmpty() || ItemFactory.isUi(stack) || isMelee(stack)) {
            return;
        }
        ItemDef def = items.defOf(stack);
        if (def != null && def.isArmor()) {
            int armorSlot = def.category() == Category.HELMET ? HEAD : CHEST;
            ItemStack worn = inv.getItem(armorSlot);
            if (worn == null || worn.isEmpty()) {
                inv.setItem(armorSlot, stack);
                inv.setItem(slot, null);
                return;
            }
        }
        List<Integer> targets = new ArrayList<>();
        boolean fromHotbar = slot < RIG_START;
        for (int cell : route(player, stack, false)) {
            boolean hotbar = cell < RIG_START;
            if (cell != slot && (fromHotbar ? !hotbar : hotbar)) {
                targets.add(cell);
            }
        }
        if (!fromHotbar) {
            for (int cell : route(player, stack, false)) {
                if (cell >= RIG_START && isRigSlot(cell) != isRigSlot(slot) && !targets.contains(cell)) {
                    targets.add(cell);
                }
            }
        }
        ItemStack moving = stack.clone();
        inv.setItem(slot, null);
        for (int cell : targets) {
            ItemStack there = inv.getItem(cell);
            if (there != null && !there.isEmpty() && there.isSimilar(moving)) {
                int moved = Math.min(there.getMaxStackSize() - there.getAmount(), moving.getAmount());
                there.setAmount(there.getAmount() + moved);
                moving.setAmount(moving.getAmount() - moved);
                if (moving.getAmount() <= 0) {
                    return;
                }
            }
        }
        for (int cell : targets) {
            ItemStack there = inv.getItem(cell);
            if (there == null || there.isEmpty()) {
                inv.setItem(cell, moving);
                return;
            }
        }
        inv.setItem(slot, moving);
    }

    // ------------------------------------------------------------------ secure container

    /** The secure container the player is using now: the raid's copy during a raid, else the profile's. */
    public List<ItemRecord> secureContents(Player player) {
        net.infnetwork.snowball.craftov.raid.RaidPlayer rp = plugin.raids().session(player);
        if (rp != null && rp.active()) {
            return rp.secure;
        }
        return plugin.profiles().get(player).secure;
    }

    /** Cells the secure container has (3 at first, 9 fully upgraded, see TraderService). */
    public int secureCapacity(Player player) {
        return plugin.traders().secureSlots(plugin.profiles().get(player));
    }

    /** Why {@code stack} may not go into the secure container, or null: no guns, armour, rigs, backpacks or armory items. */
    public @Nullable String secureRefusal(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty() || ItemFactory.isUi(stack)) {
            return "该物品无法放入安全箱";
        }
        if (ItemFactory.isTest(stack)) {
            return "试验场物资不能放入安全箱";
        }
        ItemDef def = items.defOf(stack);
        if (def == null || !def.fitsSecure()) {
            return "该物品无法放入安全箱";
        }
        return null;
    }

    /** Secure-container cells a stored record takes (its size per stack begun); one for an id items.yml no longer has. */
    public int secureCells(ItemRecord record) {
        ItemDef def = items.registry().get(record.id);
        return def == null ? 1 : def.secureCells(record.amount);
    }

    /** Cells the records take together. */
    public int secureUsed(List<ItemRecord> records) {
        int used = 0;
        for (ItemRecord record : records) {
            used += secureCells(record);
        }
        return used;
    }

    /**
     * Which record each of the nine secure cells shows (SecureMenu, LootMenu): its index, or -1 for
     * a free cell. A record shows in its first cell and holds the next ones it takes, since a chest
     * window cannot draw one icon across several slots. A container whose records take more than
     * nine cells (filled before items had sizes, at most nine records then) shows each in one cell.
     */
    public int[] secureLayout(List<ItemRecord> records) {
        int[] layout = new int[SECURE_CELLS];
        java.util.Arrays.fill(layout, -1);
        boolean compact = secureUsed(records) > SECURE_CELLS;
        int cell = 0;
        for (int i = 0; i < records.size() && cell < SECURE_CELLS; i++) {
            int cells = compact ? 1 : secureCells(records.get(i));
            for (int k = 0; k < cells && cell + k < SECURE_CELLS; k++) {
                layout[cell + k] = i;
            }
            cell += cells;
        }
        return layout;
    }

    /**
     * Puts as much of {@code record} (an item of {@code def}) into the player's secure container as
     * its cells hold, and returns how many went in; the caller takes them off where they came from
     * and saves ({@link #secureChanged}). Rounds first top up the room left in a begun cell of the
     * same kind (20 rounds and 10 more share a cell), then go into free cells, one record per
     * {@link ItemDef#secureStack} so every cell shows what it holds. A container over its cells
     * takes nothing, not even into a begun cell. The one way anything goes into it, from the secure
     * menu and from the search screen alike, so the cell count cannot be walked around.
     */
    public int secureStore(Player player, ItemDef def, ItemRecord record) {
        List<ItemRecord> records = secureContents(player);
        int cap = secureCapacity(player);
        int used = secureUsed(records);
        if (used > cap) {
            return 0;
        }
        int free = cap - used;
        int left = record.amount;
        if (def.maxStack() > 1 && record.uid == null) {
            for (ItemRecord held : records) {
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
            records.add(part);
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
        ItemStack built = items.fromRecord(x);
        return built != null && built.isSimilar(items.fromRecord(y));
    }

    /** Why nothing of {@code def} goes into the player's secure container: too big for it, over its cells, or full. */
    public String secureFullReason(Player player, ItemDef def) {
        int cap = secureCapacity(player);
        int used = secureUsed(secureContents(player));
        if (def.size() > cap) {
            return "这件物品占 " + def.size() + " 格，安全箱只有 " + cap + " 格";
        }
        if (used > cap) {
            return "安全箱超出容量（" + used + "/" + cap + " 格），先取出物品";
        }
        return "安全箱空间不足：需要 " + def.size() + " 格，剩余 " + (cap - used) + " 格";
    }

    /** Saves a change to the secure container: in the lobby it is part of the loadout. */
    public void secureChanged(Player player) {
        if (plugin.raids().raidOf(player) == null) {
            plugin.lobby().captureLoadout(player);
        }
        plugin.profiles().markDirty(player.getUniqueId());
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

    /**
     * Everything the player carries (excluding UI stacks and test items), keyed by inventory slot. The
     * gun in hand counts the rounds really in its magazine, which live in memory and reach the stack
     * only when it could move: after a reload the stack still holds the old count while the rounds
     * have already left the inventory.
     */
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
                plugin.weapons().liveMagazine(player, record);
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
