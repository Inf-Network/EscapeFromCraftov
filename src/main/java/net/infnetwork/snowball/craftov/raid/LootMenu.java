package net.infnetwork.snowball.craftov.raid;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.gui.Menu;
import net.infnetwork.snowball.craftov.gui.UiItems;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Rarity;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Search screen for a {@link LootContainer} (#10): the container on the left, the searcher's own gear
 * on the right, so looting never means guessing what is worn or what fits. Items are revealed one by
 * one while it is open.
 * <pre>
 *  cols 0-5 x 6 rows  container cells: cell k at raw (k / 6) * 9 + k % 6 (36 a page; a body with
 *                     more has 35 a page and a page button in the last cell)
 *  col 6   6 helmet  15 body armour  24 rig  33 backpack (what is worn)  42 take all  51 tidy
 *  cols 7-8  7 8 16 17 25 26 34 35 43  secure container (3/5/7/9 open)  44 its summary
 *  52 load  53 carried value           below: the player's own rig row, backpack rows and hotbar
 * </pre>
 * Container cells: left puts the item where it belongs (CarryService#route, never into the slot in
 * hand), shift-left into the secure container, right wears it (armour, rig, backpack), a number key
 * puts it on that hotbar key; with something on the cursor a click puts that into the cell. Worn
 * gear: left takes it off into the backpack (a packed rig or backpack onto the cursor), shift puts it
 * into the container packed. Own cells: plain pick-up, place and swap clicks and number-key swaps
 * between them work as in the inventory, under the zone rules; shift-left puts the stack into the
 * container. The top half only ever shows copies, so collecting to the cursor and vanilla's shift
 * move stay refused: either would duplicate what is shown.
 */
public final class LootMenu extends Menu {

    private static final int[] GEAR = {6, 15, 24, 33};
    private static final int[] SECURE = {7, 8, 16, 17, 25, 26, 34, 35, 43};
    private static final int SECURE_INFO = 44;
    private static final int TAKE_ALL = 42;
    private static final int TIDY = 51;
    private static final int LOAD = 52;
    private static final int VALUE = 53;
    private static final int PAGE_CELL = 35;
    /** Title room left of "我的装备", in pixel-font units (a hanzi is 11, a Latin letter about 6). */
    private static final int TITLE_WIDTH = 100;

    private final CraftovPlugin plugin;
    private final LootContainer container;
    private int lastProgressStep = -1;
    private int seenVersion = -1;
    private int page;
    /** A legendary find shines in its slot (enchantment glint) until this tick. */
    private int glintSlot = -1;
    private long glintUntil;

    public LootMenu(CraftovPlugin plugin, Player viewer, LootContainer container) {
        super(viewer, 6, screenFor(container.size()), title(container));
        this.plugin = plugin;
        this.container = container;
    }

    private static String screenFor(int size) {
        return size <= 9 ? "loot_9" : size <= 18 ? "loot_18" : size <= 27 ? "loot_27" : "loot_36";
    }

    private static Component title(LootContainer container) {
        String name = container.title;
        int width = 0;
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            width += ch < 0x80 ? 6 : 11;
            if (width > TITLE_WIDTH) {
                shown.append('…');
                break;
            }
            shown.append(ch);
        }
        return Text.mm("<text>" + Text.escape(shown.toString()));
    }

    /** Cells of the container on one page, the page button aside. */
    private int perPage() {
        return container.size() <= 36 ? 36 : PAGE_CELL;
    }

    private int pages() {
        return (container.size() + perPage() - 1) / perPage();
    }

    /** Raw top slot of on-page cell {@code k} (0..35). */
    static int raw(int k) {
        return (k / 6) * 9 + k % 6;
    }

    /** The container slot shown at raw top slot {@code raw}, or -1. */
    private int slotAt(int raw) {
        int col = raw % 9;
        int row = raw / 9;
        if (raw < 0 || raw >= 54 || col >= 6) {
            return -1;
        }
        int k = row * 6 + col;
        if (k >= perPage()) {
            return -1;
        }
        int slot = page * perPage() + k;
        return slot < container.size() ? slot : -1;
    }

    /** Raw top slot of container slot {@code slot}, or -1 when it is on another page. */
    private int rawOf(int slot) {
        int k = slot - page * perPage();
        return k < 0 || k >= perPage() ? -1 : raw(k);
    }

    @Override
    protected void draw() {
        seenVersion = container.version();
        page = Math.max(0, Math.min(page, pages() - 1));
        int next = container.nextHidden();
        for (int slot = page * perPage(); slot < Math.min(container.size(), (page + 1) * perPage()); slot++) {
            ItemStack stack = container.get(slot);
            if (stack == null) {
                continue;
            }
            int raw = rawOf(slot);
            if (container.isRevealed(slot)) {
                if (slot == glintSlot && Bukkit.getCurrentTick() < glintUntil) {
                    stack = stack.clone();
                    stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                }
                set(raw, stack);
            } else if (slot == next) {
                set(raw, searching());
            } else {
                set(raw, UiItems.button("craftov:ui/search_hidden", "<dim>未搜索", List.of(), "search"));
            }
        }
        if (pages() > 1) {
            tabButton(raw(PAGE_CELL), UiItems.button(page + 1 < pages() ? "craftov:ui/arrow_right" : "craftov:ui/arrow_left",
                    "<text>第 " + (page + 1) + "/" + pages() + " 页", List.of("<muted>点击翻页",
                            hiddenElsewhere() ? "<accent>另一页还有物品在搜索" : "<dim>")), e -> {
                page = (page + 1) % pages();
                redraw();
            });
        }
        drawGear();
        drawSecure();
        button(TAKE_ALL, UiItems.button("craftov:ui/info_take_all", "<text>全部拾取",
                List.of("<muted>把已搜出的物品依次放进合适的位置", "<dim>放不下的留在箱子里")), e -> takeAll());
        button(TIDY, plugin.carryInfo().tidy(), e -> {
            plugin.carry().tidy(viewer);
            viewer.updateInventory();
            Notify.ok(viewer, "已整理胸挂与背包");
        });
        set(LOAD, plugin.carryInfo().load(viewer));
        set(VALUE, plugin.carryInfo().value(viewer));
    }

    private boolean hiddenElsewhere() {
        int next = container.nextHidden();
        return next >= 0 && rawOf(next) < 0;
    }

    private void drawGear() {
        PlayerInventory inv = viewer.getInventory();
        int[] worn = {CarryService.HEAD, CarryService.CHEST, CarryService.LEGS, CarryService.FEET};
        for (int i = 0; i < GEAR.length; i++) {
            ItemStack stack = inv.getItem(worn[i]);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack shown = stack.clone();
            List<Component> lore = new ArrayList<>(shown.getData(DataComponentTypes.LORE) == null ? List.of()
                    : shown.getData(DataComponentTypes.LORE).lines());
            lore.add(Component.empty());
            lore.add(Text.mm("<ok>左键 卸下 <muted>· Shift 放进箱子"));
            shown.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
            set(GEAR[i], shown);
        }
    }

    private void drawSecure() {
        List<ItemRecord> records = plugin.carry().secureContents(viewer);
        int cap = plugin.carry().secureCapacity(viewer);
        for (int i = 0; i < SECURE.length; i++) {
            if (i >= cap) {
                set(SECURE[i], UiItems.lockedCell(UiItems.Zone.SECURE, i == cap, "安全箱", cap, SECURE.length));
            } else if (i < records.size()) {
                ItemStack stack = plugin.items().fromRecord(records.get(i));
                if (stack == null) {
                    stack = UiItems.button("craftov:ui/collection_unknown", "<muted>未知物品 <white>" + Text.escape(records.get(i).id),
                            List.of("<muted>物品表里暂时没有这件物品"), "search");
                }
                set(SECURE[i], stack);
            }
        }
        set(SECURE_INFO, UiItems.button("craftov:ui/icon_secure", "<info>安全箱 <white>" + records.size() + "/" + cap,
                List.of("<muted>阵亡不掉落", "<muted>箱子里的物品 Shift+左键 直接放进来", "<dim>枪械、护甲、胸挂与背包放不进")));
    }

    private ItemStack searching() {
        double speed = plugin.operators().searchMultiplier(viewer);
        return UiItems.button("craftov:ui/search_active", "<accent>搜索中… <white>" + String.format("%.1f", container.secondsLeft(speed)) + "s",
                List.of("<muted>品质越高搜索越久，保持界面打开以继续"), "search");
    }

    /** Opening a container plays loot.open (RaidManager), not the menu sound. */
    @Override
    protected boolean openSound() {
        return false;
    }

    @Override
    public void onClose() {
        container.refreshLook();
    }

    @Override
    public boolean ticking() {
        return true;
    }

    @Override
    public void tick() {
        if (!viewer.isOnline() || viewer.getWorld() != container.location.getWorld()
                || viewer.getLocation().distanceSquared(container.location) > 49 || plugin.downed().isDowned(viewer)) {
            viewer.closeInventory();
            return;
        }
        if (container.version() != seenVersion || glintSlot >= 0 && Bukkit.getCurrentTick() >= glintUntil) {
            if (Bukkit.getCurrentTick() >= glintUntil) {
                glintSlot = -1;
            }
            redraw(); // another searcher revealed or took something, or a find stopped shining
        } else if (Bukkit.getCurrentTick() % 10 == 0) {
            set(LOAD, plugin.carryInfo().load(viewer));
            set(VALUE, plugin.carryInfo().value(viewer));
        }
        if (container.hiddenCount() == 0) {
            return;
        }
        double speed = plugin.operators().searchMultiplier(viewer);
        int revealed = container.searchTick(speed, Bukkit.getCurrentTick());
        if (revealed >= 0) {
            ItemDef def = plugin.items().defOf(container.get(revealed));
            plugin.luck().onFound(viewer, def);
            Rarity rarity = def == null ? Rarity.WHITE : def.rarity();
            String sound = switch (rarity) {
                case GOLD -> "craftov:loot.reveal_gold";
                case RED -> "craftov:loot.reveal_red";
                case PURPLE -> "craftov:loot.reveal_purple";
                default -> "craftov:loot.reveal";
            };
            viewer.playSound(viewer.getLocation(), sound, SoundCategory.PLAYERS, 0.8f, 1f);
            if (rarity == Rarity.RED) {
                // A title would be hidden behind this very window: the find shines in its slot instead.
                glintSlot = revealed;
                glintUntil = Bukkit.getCurrentTick() + 10;
            }
            redraw();
            lastProgressStep = -1;
            return;
        }
        int step = (int) (Bukkit.getCurrentTick() / 4);
        if (step != lastProgressStep) {
            lastProgressStep = step;
            int next = container.nextHidden();
            if (next >= 0 && rawOf(next) >= 0) {
                set(rawOf(next), searching());
            }
            if (Bukkit.getCurrentTick() % 4 == 0) {
                viewer.playSound(viewer.getLocation(), "craftov:loot.search_tick", SoundCategory.PLAYERS, 0.25f, 1f);
            }
        }
    }

    // ------------------------------------------------------------------ clicks in the top half

    @Override
    protected void onTopClick(InventoryClickEvent event) {
        int raw = event.getSlot();
        for (int i = 0; i < GEAR.length; i++) {
            if (GEAR[i] == raw) {
                gearClick(i, event.getClick());
                return;
            }
        }
        for (int i = 0; i < SECURE.length; i++) {
            if (SECURE[i] == raw) {
                secureClick(i, event.getClick());
                return;
            }
        }
        int slot = slotAt(raw);
        if (slot >= 0) {
            containerClick(slot, event);
        }
    }

    private void containerClick(int slot, InventoryClickEvent event) {
        ClickType click = event.getClick();
        if (click == ClickType.DOUBLE_CLICK || click == ClickType.MIDDLE || click == ClickType.DROP
                || click == ClickType.CONTROL_DROP || click == ClickType.CREATIVE) {
            return; // a double click is the second half of a click already handled
        }
        ItemStack cursor = viewer.getItemOnCursor();
        if (!cursor.isEmpty()) {
            putCursor(slot, cursor);
            return;
        }
        ItemStack stack = container.get(slot);
        if (stack == null || !container.isRevealed(slot)) {
            return;
        }
        ItemDef def = plugin.items().defOf(stack);
        switch (click) {
            case SHIFT_LEFT -> intoSecure(slot, stack);
            case NUMBER_KEY -> toHotbar(slot, stack, event.getHotbarButton());
            case RIGHT, SHIFT_RIGHT -> {
                if (def != null && (def.isArmor() || def.isContainer())) {
                    wear(slot, stack, def);
                } else {
                    take(slot, stack);
                }
            }
            default -> {
                if (def != null && def.isContainer() && ItemFactory.hasContents(stack)) {
                    wear(slot, stack, def); // a packed rig or backpack fits nowhere but on the back
                } else {
                    take(slot, stack);
                }
            }
        }
    }

    /** Left click: into the cells it belongs in (CarryService#route), never the slot in hand. */
    private void take(int slot, ItemStack stack) {
        ItemStack left = plugin.carry().giveOrWear(viewer, stack, false, true);
        if (left != null && left.getAmount() == stack.getAmount()) {
            refuse("身上没有放它的位置");
            return;
        }
        container.set(slot, left);
        looted(stack, 0.7f, 1f);
    }

    /** Right click on armour, a rig or a backpack: wear it; what was worn goes into its cell. */
    private void wear(int slot, ItemStack stack, ItemDef def) {
        CarryService carry = plugin.carry();
        ItemStack old;
        if (def.isContainer()) {
            old = carry.unequip(viewer, CarryService.containerSlot(def.category()));
            carry.equip(viewer, stack);
            viewer.playSound(viewer.getLocation(), "minecraft:item.armor.equip_leather", 1f, 1f);
        } else {
            int armorSlot = def.category() == Category.HELMET ? CarryService.HEAD : CarryService.CHEST;
            ItemStack worn = viewer.getInventory().getItem(armorSlot);
            old = worn == null || worn.isEmpty() ? null : worn.clone();
            viewer.getInventory().setItem(armorSlot, stack);
            viewer.playSound(viewer.getLocation(), "minecraft:item.armor.equip_iron", 1f, 1f);
        }
        container.set(slot, old);
        plugin.raids().onLooted(viewer, stack);
        viewer.updateInventory();
        redraw();
    }

    /** Shift-left: straight into the secure container. */
    private void intoSecure(int slot, ItemStack stack) {
        String why = secureRoom(stack);
        if (why != null) {
            refuse(why);
            return;
        }
        ItemRecord record = plugin.items().toRecord(stack);
        if (record == null) {
            return;
        }
        plugin.carry().secureContents(viewer).add(record);
        plugin.carry().secureChanged(viewer);
        container.set(slot, null);
        looted(stack, 0.6f, 1.1f);
    }

    private @Nullable String secureRoom(ItemStack stack) {
        String why = plugin.carry().secureRefusal(stack);
        if (why != null) {
            return why;
        }
        return plugin.carry().secureContents(viewer).size() >= plugin.carry().secureCapacity(viewer) ? "安全箱已满" : null;
    }

    /** A number key: onto that hotbar key if it takes the item, swapping with what is there. */
    private void toHotbar(int slot, ItemStack stack, int key) {
        if (key < 0 || key > 8) {
            return;
        }
        CarryService carry = plugin.carry();
        String why = carry.refusal(stack, key);
        if (why != null) {
            refuse(why);
            return;
        }
        PlayerInventory inv = viewer.getInventory();
        ItemStack there = inv.getItem(key);
        if (there == null || there.isEmpty()) {
            inv.setItem(key, stack);
            container.set(slot, null);
        } else if (there.isSimilar(stack) && there.getAmount() < there.getMaxStackSize()) {
            int moved = Math.min(there.getMaxStackSize() - there.getAmount(), stack.getAmount());
            there.setAmount(there.getAmount() + moved);
            ItemStack rest = stack.clone();
            rest.setAmount(stack.getAmount() - moved);
            container.set(slot, rest.getAmount() > 0 ? rest : null);
        } else if (ItemFactory.isUi(there) || carry.isMelee(there)) {
            refuse("这个格子不能换出");
            return;
        } else {
            inv.setItem(key, stack);
            container.set(slot, there.clone());
        }
        looted(stack, 0.7f, 1f);
    }

    /** Something on the cursor clicked onto a container cell: it goes in, swapping with what is there. */
    private void putCursor(int slot, ItemStack cursor) {
        ItemStack there = container.get(slot);
        if (there != null && !container.isRevealed(slot)) {
            return;
        }
        ItemStack moving = cursor.clone();
        if (there == null) {
            container.set(slot, moving);
            viewer.setItemOnCursor(null);
        } else if (there.isSimilar(moving) && there.getAmount() < there.getMaxStackSize()) {
            int moved = Math.min(there.getMaxStackSize() - there.getAmount(), moving.getAmount());
            ItemStack merged = there.clone();
            merged.setAmount(there.getAmount() + moved);
            container.set(slot, merged);
            moving.setAmount(moving.getAmount() - moved);
            viewer.setItemOnCursor(moving.getAmount() > 0 ? moving : null);
        } else {
            container.set(slot, moving);
            viewer.setItemOnCursor(there.clone());
            plugin.raids().onLooted(viewer, there);
        }
        viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", SoundCategory.PLAYERS, 0.5f, 0.8f);
        redraw();
    }

    /** Worn gear: left takes it off into a cell (a packed rig or backpack onto the cursor), shift puts it into the container. */
    private void gearClick(int index, ClickType click) {
        int[] worn = {CarryService.HEAD, CarryService.CHEST, CarryService.LEGS, CarryService.FEET};
        int armorSlot = worn[index];
        PlayerInventory inv = viewer.getInventory();
        ItemStack cursor = viewer.getItemOnCursor();
        CarryService carry = plugin.carry();
        if (!cursor.isEmpty()) {
            wearFromCursor(armorSlot, cursor);
            return;
        }
        ItemStack current = inv.getItem(armorSlot);
        if (current == null || current.isEmpty()) {
            return;
        }
        boolean bag = armorSlot == CarryService.LEGS || armorSlot == CarryService.FEET;
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            int target = container.firstEmpty();
            if (target < 0) {
                refuse("箱子已满");
                return;
            }
            ItemStack removed = bag ? carry.unequip(viewer, armorSlot) : current.clone();
            if (!bag) {
                inv.setItem(armorSlot, null);
            }
            container.set(target, removed);
            viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", SoundCategory.PLAYERS, 0.5f, 0.8f);
        } else if (click == ClickType.LEFT || click == ClickType.RIGHT) {
            ItemStack removed = bag ? carry.unequip(viewer, armorSlot) : current.clone();
            if (removed == null) {
                return;
            }
            if (!bag) {
                inv.setItem(armorSlot, null);
            }
            ItemStack left = ItemFactory.hasContents(removed) ? removed : carry.give(viewer, removed, false, true);
            if (left != null) {
                viewer.setItemOnCursor(left); // packed, or no room: in hand, to put into the container or back on
            }
            viewer.playSound(viewer.getLocation(), "minecraft:item.armor.equip_leather", 0.8f, 1.2f);
        } else {
            return;
        }
        viewer.updateInventory();
        redraw();
    }

    private void wearFromCursor(int armorSlot, ItemStack cursor) {
        ItemDef def = plugin.items().defOf(cursor);
        CarryService carry = plugin.carry();
        boolean fits = def != null && (def.isContainer() ? CarryService.containerSlot(def.category()) == armorSlot
                : def.isArmor() && (def.category() == Category.HELMET ? CarryService.HEAD : CarryService.CHEST) == armorSlot);
        if (!fits) {
            refuse("这里只能穿戴对应的装备");
            return;
        }
        ItemStack moving = cursor.clone();
        if (def.isContainer()) {
            ItemStack old = carry.unequip(viewer, armorSlot);
            viewer.setItemOnCursor(old);
            carry.equip(viewer, moving);
        } else {
            ItemStack worn = viewer.getInventory().getItem(armorSlot);
            viewer.setItemOnCursor(worn == null || worn.isEmpty() ? null : worn.clone());
            viewer.getInventory().setItem(armorSlot, moving);
        }
        viewer.playSound(viewer.getLocation(), "minecraft:item.armor.equip_leather", 1f, 1f);
        viewer.updateInventory();
        redraw();
    }

    /** Secure cells: a click with something on the cursor puts it in; left takes an item out, shift puts it into the container. */
    private void secureClick(int index, ClickType click) {
        CarryService carry = plugin.carry();
        List<ItemRecord> records = carry.secureContents(viewer);
        if (index >= carry.secureCapacity(viewer)) {
            return;
        }
        ItemStack cursor = viewer.getItemOnCursor();
        if (!cursor.isEmpty()) {
            String why = secureRoom(cursor);
            ItemRecord record = why == null ? plugin.items().toRecord(cursor) : null;
            if (why != null || record == null) {
                refuse(why == null ? "该物品无法放入安全箱" : why);
                return;
            }
            records.add(record);
            viewer.setItemOnCursor(null);
            carry.secureChanged(viewer);
            viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", SoundCategory.PLAYERS, 0.6f, 1.1f);
            redraw();
            return;
        }
        if (index >= records.size() || click == ClickType.DOUBLE_CLICK) {
            return;
        }
        ItemStack stack = plugin.items().fromRecord(records.get(index));
        if (stack == null) {
            carry.mail(viewer, records.remove(index));
            carry.secureChanged(viewer);
            redraw();
            return;
        }
        int moved;
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            int target = container.firstEmpty();
            if (target < 0) {
                refuse("箱子已满");
                return;
            }
            container.set(target, stack);
            moved = stack.getAmount();
        } else {
            ItemStack left = carry.give(viewer, stack, false, true);
            moved = stack.getAmount() - (left == null ? 0 : left.getAmount());
            if (moved <= 0) {
                refuse("身上没有放它的位置");
                return;
            }
        }
        // Only what was moved leaves the record: one above the stack size (older builds, or a stack
        // size items.yml lowered since) builds a single stack and keeps the rest.
        ItemRecord record = records.get(index);
        record.amount -= moved;
        if (record.amount <= 0) {
            records.remove(index);
        }
        carry.secureChanged(viewer);
        viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", SoundCategory.PLAYERS, 0.6f, 1f);
        redraw();
    }

    private void takeAll() {
        if (!viewer.getItemOnCursor().isEmpty()) {
            refuse("请先放下手上的物品");
            return;
        }
        int taken = 0;
        boolean full = false;
        for (int slot = 0; slot < container.size(); slot++) {
            ItemStack stack = container.get(slot);
            if (stack == null || !container.isRevealed(slot) || ItemFactory.hasContents(stack)) {
                continue;
            }
            ItemStack left = plugin.carry().giveOrWear(viewer, stack, false, true);
            if (left != null && left.getAmount() == stack.getAmount()) {
                full = true;
                continue;
            }
            container.set(slot, left);
            plugin.raids().onLooted(viewer, stack);
            taken++;
        }
        if (taken > 0) {
            viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", SoundCategory.PLAYERS, 0.8f, 1f);
        }
        redraw();
        if (taken == 0) {
            refuse(full ? "身上放不下了" : "没有可拾取的物品");
        } else {
            Notify.ok(viewer, "拾取了 " + taken + " 件" + (full ? "，其余放不下" : ""));
        }
    }

    private void looted(ItemStack stack, float volume, float pitch) {
        viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", SoundCategory.PLAYERS, volume, pitch);
        plugin.raids().onLooted(viewer, stack);
        viewer.updateInventory();
        redraw();
    }

    /** In the clicked cell (or the title), on the action bar, with the error sound. */
    private void refuse(String why) {
        Notify.error(viewer, why);
    }

    // ------------------------------------------------------------------ the player's own cells

    @Override
    protected void onBottomClick(InventoryClickEvent event) {
        if (event.getClickedInventory() != viewer.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = viewer.getItemOnCursor();
        if (slot >= CarryService.FEET || ItemFactory.isUi(current) || ItemFactory.isUi(cursor)
                || event.getClick() == ClickType.SWAP_OFFHAND || event.getHotbarButton() == 40) {
            return;
        }
        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
            intoContainer(slot, current);
            return;
        }
        InventoryAction action = event.getAction();
        CarryService carry = plugin.carry();
        boolean plain = switch (action) {
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE, PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR, HOTBAR_SWAP -> true;
            default -> false;
        };
        if (!plain) {
            return; // collecting to the cursor or vanilla's shift move would take the copies shown above
        }
        if (slot == CarryService.MELEE_SLOT || carry.isMelee(current) || carry.isMelee(cursor)) {
            return;
        }
        if (action == InventoryAction.HOTBAR_SWAP) {
            ItemStack hotbar = viewer.getInventory().getItem(event.getHotbarButton());
            if (event.getHotbarButton() == CarryService.MELEE_SLOT || ItemFactory.isUi(hotbar) || carry.isMelee(hotbar)
                    || ItemFactory.hasContents(hotbar) || ItemFactory.hasContents(current)) {
                return;
            }
        } else if (ItemFactory.hasContents(cursor) && action != InventoryAction.PICKUP_ALL && action != InventoryAction.PICKUP_HALF) {
            refuse("装有物品的胸挂/背包不能放进携行格");
            return;
        }
        String why = carry.clickRefusal(viewer, action, slot, current, cursor, event.getHotbarButton());
        if (why != null) {
            refuse(why);
            return;
        }
        event.setCancelled(false); // a move within the player's own cells: vanilla does it
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.getOpenInventory().getTopInventory().getHolder() == this) {
                set(LOAD, plugin.carryInfo().load(viewer));
                set(VALUE, plugin.carryInfo().value(viewer));
            }
        });
    }

    /** Shift-left on an own cell: the whole stack goes into the container's first free cell. */
    private void intoContainer(int slot, @Nullable ItemStack current) {
        if (current == null || current.isEmpty() || plugin.carry().isMelee(current)) {
            return;
        }
        int target = container.firstEmpty();
        if (target < 0) {
            refuse("箱子已满");
            return;
        }
        container.set(target, current.clone());
        viewer.getInventory().setItem(slot, null);
        viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", SoundCategory.PLAYERS, 0.5f, 0.8f);
        viewer.updateInventory();
        redraw();
    }

    @Override
    public boolean drag(InventoryDragEvent event) {
        ItemStack moving = event.getOldCursor();
        if (ItemFactory.isUi(moving) || ItemFactory.hasContents(moving) || plugin.carry().isMelee(moving)) {
            return false;
        }
        int top = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < top) {
                return false;
            }
            int slot = event.getView().convertSlot(raw);
            if (slot >= CarryService.FEET || ItemFactory.isUi(viewer.getInventory().getItem(slot))
                    || plugin.carry().refusal(moving, slot) != null) {
                return false;
            }
        }
        return true;
    }
}
