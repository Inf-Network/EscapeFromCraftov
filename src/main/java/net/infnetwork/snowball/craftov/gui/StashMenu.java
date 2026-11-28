package net.infnetwork.snowball.craftov.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.profile.MailService;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The stash (#10).
 * <pre>
 *  0-8    category tabs: all, weapons (melee too), ammo, armour, carry, meds, valuables and
 *         collectibles, keys and special items, attachments and grenades
 *  rows 1-4, columns 0-5   24 items a page
 *  column 6 (15 24 33 42)  worn helmet, vest, rig, backpack      columns 7-8  keys 1-8, two a row
 *  45 ◀   46 sort (value / rarity / category / newest)   47 multi-select   48 sell the selection
 *  49 room used and total value   50 mailbox   51 secure container   52 more room   53 ▶
 * </pre>
 * The loadout panel on the right (#185) shows what the player wears and has on keys 1-8, so a
 * loadout can be put together here: a click on one of them puts it into the stash (a rig or
 * backpack with everything packed inside it), and taking gear out puts it on. Taking out a helmet,
 * vest, rig or backpack with no room for it on the player swaps it for the worn one, which goes
 * into the stash. The knife stays on key 3.
 * There are no modes: left takes an item out, right lists it on the market, shift (or any click
 * while multi-select is on) picks it. Selling only ever sells the selection, and only on a second
 * click of the sell button within five seconds of the first. Items wear a corner mark for their value
 * band (one to four pips) and, a rig or backpack with things inside, a plus with the count. The title
 * gives the room used, the total value and the page; the chosen tab is lit by the screen behind it.
 * Clicking an item in the inventory below puts it in (shift: every stack of it).
 */
@SuppressWarnings("UnstableApiUsage")
final class StashMenu extends Menu {

    enum Tab {
        ALL("全部", "tab_all"),
        WEAPON("武器", "tab_weapon", Category.WEAPON, Category.MELEE),
        AMMO("弹药", "tab_ammo", Category.AMMO),
        ARMOR("防具", "tab_armor", Category.HELMET, Category.ARMOR),
        CARRY("携行", "tab_carry", Category.RIG, Category.BACKPACK),
        MEDICAL("医疗", "tab_medical", Category.MEDICAL),
        LOOT("物资 · 收藏", "tab_loot", Category.VALUABLE, Category.COLLECTIBLE),
        KEY("钥匙 · 特殊", "tab_key", Category.KEY, Category.SPECIAL),
        PARTS("配件 · 投掷物", "tab_attachment", Category.ATTACHMENT, Category.THROWABLE);

        final String label;
        final String icon;
        final List<Category> categories;

        Tab(String label, String icon, Category... categories) {
            this.label = label;
            this.icon = icon;
            this.categories = List.of(categories);
        }

        boolean holds(@Nullable ItemDef def) {
            return categories.isEmpty() || def != null && categories.contains(def.category());
        }
    }

    enum Sort {
        VALUE("价值"), RARITY("品质"), CATEGORY("类别"), NEWEST("最新");

        final String label;

        Sort(String label) {
            this.label = label;
        }
    }

    /** Value bands for the corner pips (stash.value-bands): at or above each, one more pip, four at most (tools/art/ui.py). */
    private static final List<Long> VALUE_BANDS = List.of(10_000L, 50_000L, 150_000L, 500_000L);
    private static final int MAX_PIPS = 4;
    /** custom_model_data strings the item models read the corner marks from (tools/art/build.py, gui_case). */
    private static final int BADGE_VALUE = 6;
    private static final int BADGE_PLUS = 7;
    private static final int CELLS = 24;
    /** The panel's worn column, top to bottom, and the inventory slot each shows (tools/art/screens.py STASH_GEAR). */
    private static final int[][] GEAR = {{15, CarryService.HEAD}, {24, CarryService.CHEST}, {33, CarryService.LEGS}, {42, CarryService.FEET}};
    /** The panel's keys 1-8, two a row (tools/art/screens.py STASH_KEYS). */
    private static final int[] KEYS = {16, 17, 25, 26, 34, 35, 43, 44};

    /** The menu slot of stored-item cell {@code i} on a page: six columns of rows 1-4. */
    private static int cellSlot(int i) {
        return 9 + (i / 6) * 9 + i % 6;
    }

    /** How long the sell button waits for its second click, in ticks (stash.sell-confirm-seconds). */
    private int confirmTicks() {
        return (int) Math.max(20, Math.round(plugin.getConfig().getDouble("stash.sell-confirm-seconds", 5) * 20));
    }

    private int pips(long value) {
        List<?> raw = plugin.getConfig().getList("stash.value-bands", VALUE_BANDS);
        int band = 0;
        for (Object floor : raw) {
            if (floor instanceof Number n && value >= n.longValue()) {
                band++;
            }
        }
        return Math.min(MAX_PIPS, band);
    }

    private final CraftovPlugin plugin;
    private int page;
    private Tab tab = Tab.ALL;
    private Sort sort = Sort.NEWEST;
    private boolean selecting;
    private final Set<ItemRecord> selected = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The sell button was clicked once and sells on the next click until this tick. */
    private long sellArmedUntil = -1;

    StashMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 6, "stash_0", Text.mm("仓库"));
        this.plugin = plugin;
    }

    private Profile profile() {
        return plugin.profiles().get(viewer);
    }

    /** The records the chosen tab shows, in the chosen order. */
    private List<ItemRecord> shown(Profile p) {
        List<ItemRecord> out = new ArrayList<>();
        for (ItemRecord record : p.stash) {
            if (tab.holds(plugin.registry().get(record.id))) {
                out.add(record);
            }
        }
        Comparator<ItemRecord> byValue = Comparator.comparingLong(r -> -plugin.items().value(r));
        Comparator<ItemRecord> byRarity = Comparator.comparingInt(r -> {
            ItemDef d = plugin.registry().get(r.id);
            return d == null ? 0 : -d.rarity().ordinal();
        });
        Comparator<ItemRecord> byCategory = Comparator.comparingInt(r -> {
            ItemDef d = plugin.registry().get(r.id);
            return d == null ? 99 : d.category().ordinal();
        });
        switch (sort) {
            case VALUE -> out.sort(byValue);
            case RARITY -> out.sort(byRarity.thenComparing(byValue));
            case CATEGORY -> out.sort(byCategory.thenComparing(byRarity).thenComparing(byValue));
            case NEWEST -> Collections.reverse(out);
        }
        return out;
    }

    private long total(Profile p) {
        long total = 0;
        for (ItemRecord record : p.stash) {
            total += plugin.items().value(record);
        }
        return total;
    }

    @Override
    protected void draw() {
        Profile p = profile();
        // A record that left the stash (taken, sold, listed) is no longer picked.
        Set<ItemRecord> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(p.stash);
        selected.retainAll(present);
        List<ItemRecord> shown = shown(p);
        int pages = Math.max(1, (shown.size() + CELLS - 1) / CELLS);
        page = Math.max(0, Math.min(page, pages - 1));
        long total = total(p);
        restyle("stash_" + tab.ordinal(), Text.mm("仓库 <muted>" + p.stashUsed() + "/" + p.stashCapacity
                + " · <accent>◈" + Text.moneyShort(total) + " <muted>· " + (page + 1) + "/" + pages));
        for (Tab t : Tab.values()) {
            boolean on = t == tab;
            int count = 0;
            for (ItemRecord record : p.stash) {
                if (t.holds(plugin.registry().get(record.id))) {
                    count++;
                }
            }
            tabButton(t.ordinal(), UiItems.button("craftov:ui/" + t.icon, (on ? "<accent>▶ " : "<text>") + t.label,
                    List.of("<muted>" + count + " 件")), e -> {
                tab = t;
                page = 0;
                redraw();
            });
        }
        for (int i = 0; i < CELLS; i++) {
            int index = page * CELLS + i;
            if (index >= shown.size()) {
                break;
            }
            ItemRecord record = shown.get(index);
            ItemStack display = display(record);
            if (display != null) {
                button(cellSlot(i), display, e -> clickItem(record, e));
            }
        }
        for (int[] gear : GEAR) {
            drawCarried(gear[0], gear[1]);
        }
        for (int k = 0; k < KEYS.length; k++) {
            drawCarried(KEYS[k], k);
        }
        fillRow(5, UiItems.filler());
        tabButton(45, UiItems.button("craftov:ui/arrow_left", "<text>上一页", List.of("<muted>" + (page + 1) + "/" + pages)), e -> {
            page = Math.max(0, page - 1);
            redraw();
        });
        tabButton(53, UiItems.button("craftov:ui/arrow_right", "<text>下一页", List.of("<muted>" + (page + 1) + "/" + pages)), e -> {
            page = Math.min(pages - 1, page + 1);
            redraw();
        });
        List<String> sortLore = new ArrayList<>();
        for (Sort s : Sort.values()) {
            sortLore.add((s == sort ? "<accent>▶ " : "<dim>  ") + s.label);
        }
        sortLore.add("<muted>点击切换排序 · 只改变显示顺序");
        tabButton(46, UiItems.button("craftov:ui/icon_sort", "<text>排序：<accent>" + sort.label, sortLore), e -> {
            sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
            page = 0;
            redraw();
        });
        ItemStack select = UiItems.button("craftov:ui/info_select", (selecting ? "<accent>多选：开" : "<text>多选：关")
                + (selected.isEmpty() ? "" : " <muted>· 已选 <white>" + selected.size()),
                List.of("<muted>开启后左键点物品即可选中", "<muted>任何时候 Shift+点击 也能选中", "<muted>右键清空已选"));
        if (selecting) {
            select.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        button(47, select, e -> {
            if (e.isRightClick()) {
                selected.clear();
                sellArmedUntil = -1;
            } else {
                selecting = !selecting;
            }
            redraw();
        });
        drawSell();
        button(49, UiItems.button("craftov:ui/icon_stash", "<text>仓库 " + p.stashUsed() + "/" + p.stashCapacity,
                List.of("<muted>块币 <accent>◈" + Text.money(p.money), "<muted>仓库总价值 <white>◈" + Text.money(total),
                        "<muted>点击下方背包物品存入，Shift 存入同类全部",
                        "<muted>点击右侧装备区存入身上的装备")), e -> { });
        int letters = MailService.unclaimed(p);
        List<String> mailLore = new ArrayList<>(List.of("<muted>系统邮件 <white>" + letters + "</white> 封未读",
                "<muted>物品邮箱 <white>" + p.mailbox.size() + "</white> 件"));
        long nextClaim = net.infnetwork.snowball.craftov.economy.InsuranceService.nextClaim(p);
        if (nextClaim > 0) {
            long minutes = Math.max(1, (nextClaim - System.currentTimeMillis() + 59_999) / 60_000);
            mailLore.add("<accent>理赔途中 " + p.claims.size() + " 件，最早约 " + minutes + " 分钟后到账");
        }
        mailLore.add("<muted>点击打开");
        ItemStack mailbox = UiItems.button("craftov:ui/icon_mail", "<white>邮箱 <accent>" + (letters + p.mailbox.size()), mailLore);
        if (letters > 0) {
            mailbox.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        button(50, mailbox, e -> new MailMenu(plugin, viewer).open());
        button(51, UiItems.button("craftov:ui/icon_secure", "<info>安全箱", List.of("<muted>打开安全箱")), e -> plugin.menus().openSecure(viewer));
        button(52, UiItems.button("craftov:ui/icon_upgrade", "<white>仓库扩容 +30", List.of("<muted>价格 ◈" + Text.money(plugin.traders().stashUpgradePrice(p)))),
                e -> {
                    plugin.traders().upgradeStash(viewer);
                    redraw();
                });
    }

    private void drawSell() {
        long price = 0;
        for (ItemRecord record : selected) {
            price += plugin.traders().sellPrice(record);
        }
        boolean armed = Bukkit.getCurrentTick() < sellArmedUntil && !selected.isEmpty();
        ItemStack sell = armed
                ? UiItems.button("craftov:ui/info_sell_confirm", "<danger><b>再点一次确认出售",
                List.of("<text>" + selected.size() + " 件 <muted>· 商人出价 <accent>◈" + Text.money(price), "<dim>" + Math.round(confirmTicks() / 20.0) + " 秒内不点则取消"))
                : UiItems.button("craftov:ui/icon_sell", selected.isEmpty() ? "<muted>出售所选" : "<text>出售所选 <white>" + selected.size() + " 件",
                List.of(selected.isEmpty() ? "<muted>Shift+点击物品选中，或先开启多选" : "<muted>商人出价 <accent>◈" + Text.money(price),
                        "<dim>需要点两次才会成交"));
        if (armed) {
            sell.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        button(48, sell, e -> clickSell());
    }

    /** A stash item as shown: its lore plus what the clicks do, the value pips and, packed, the plus and count. */
    private @Nullable ItemStack display(ItemRecord record) {
        ItemStack display = plugin.items().fromRecord(record);
        if (display == null) {
            return null;
        }
        boolean picked = selected.contains(record);
        List<Component> lore = new ArrayList<>(display.getData(DataComponentTypes.LORE) == null ? List.of()
                : display.getData(DataComponentTypes.LORE).lines());
        if (record.footprint() > 1) {
            lore.add(Text.mm("<accent>内含 " + (record.footprint() - 1) + " 件 · 占用仓库 " + record.footprint() + " 格"));
        }
        lore.add(Component.empty());
        lore.add(Text.mm("<ok>左键 取出 <muted>· <info>右键 上架 <muted>· Shift 选中"));
        lore.add(Text.mm("<muted>商人收购价 <accent>◈" + Text.money(plugin.traders().sellPrice(record))));
        if (picked) {
            lore.add(Text.mm("<accent>✔ 已选中"));
            display.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        display.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        int band = pips(plugin.items().value(record));
        int inside = record.contents == null ? 0 : record.contents.size();
        if (band > 0 || inside > 0) {
            List<String> strings = new ArrayList<>();
            CustomModelData had = display.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
            if (had != null) {
                strings.addAll(had.strings());
            }
            while (strings.size() <= BADGE_PLUS) {
                strings.add("");
            }
            strings.set(BADGE_VALUE, band > 0 ? "v" + band : "");
            strings.set(BADGE_PLUS, inside > 0 ? "plus" : "");
            CustomModelData.Builder cmd = CustomModelData.customModelData().addStrings(strings);
            if (had != null) {
                cmd.addFloats(had.floats()).addFlags(had.flags()).addColors(had.colors());
            }
            display.setData(DataComponentTypes.CUSTOM_MODEL_DATA, cmd.build());
        }
        if (inside > 1) {
            display.setData(DataComponentTypes.MAX_STACK_SIZE, 99);
            display.setAmount(Math.min(99, inside));
        }
        return display;
    }

    private void clickItem(ItemRecord record, InventoryClickEvent event) {
        Profile p = profile();
        int index = indexOf(p, record);
        if (index < 0) {
            redraw();
            return;
        }
        if (event.isShiftClick() || selecting && event.isLeftClick()) {
            if (!selected.remove(record)) {
                selected.add(record);
            }
            sellArmedUntil = -1;
            redraw();
            return;
        }
        if (event.isRightClick()) {
            if (record.isKit()) {
                Notify.error(viewer, "免费补给的物品不能上架交易行");
                return;
            }
            if (!plugin.market().refuseIfClosed(viewer)) {
                new PriceMenu(plugin, viewer, index, record, this).open();
            }
            return;
        }
        if (!event.isLeftClick()) {
            return;
        }
        takeOut(p, index, record);
    }

    private static int indexOf(Profile p, ItemRecord record) {
        for (int i = 0; i < p.stash.size(); i++) {
            if (p.stash.get(i) == record) {
                return i;
            }
        }
        return -1;
    }

    private void takeOut(Profile p, int index, ItemRecord record) {
        ItemStack stack = plugin.items().fromRecord(record);
        if (stack == null) {
            return;
        }
        ItemDef def = plugin.items().defOf(stack);
        int wear = plugin.carry().wearSlot(stack);
        if (ItemFactory.hasContents(stack) && def != null && def.isContainer()) {
            // Packed, it fits nowhere but its own slot: the worn one is swapped into the stash.
            if (!swapWorn(stack, CarryService.containerSlot(def.category()))) {
                return;
            }
            p.stash.remove(indexOf(p, record));
        } else {
            ItemStack left = plugin.carry().giveOrWear(viewer, stack, false, false);
            int moved = stack.getAmount() - (left == null ? 0 : left.getAmount());
            if (moved <= 0 && wear >= 0) {
                if (!swapWorn(stack, wear)) {
                    return;
                }
                moved = stack.getAmount();
                index = indexOf(p, record);
            }
            if (moved <= 0) {
                Notify.error(viewer, "身上没有放它的位置");
                return;
            }
            // Only what was handed over leaves the record: a record above the stack size (older
            // builds stored those) builds a single stack and keeps the rest.
            record.amount -= moved;
            if (record.amount <= 0) {
                p.stash.remove(index);
            }
        }
        sellArmedUntil = -1; // a picked record may have shrunk: the sale is confirmed again
        viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", 0.6f, 1f);
        plugin.lobby().captureLoadout(viewer);
        plugin.profiles().markDirty(viewer.getUniqueId());
        redraw();
    }

    /** A worn or hotbar item in the loadout panel, with what a click on it does. */
    private void drawCarried(int slot, int invSlot) {
        ItemStack carried = viewer.getInventory().getItem(invSlot);
        if (carried == null || carried.isEmpty() || ItemFactory.isUi(carried)) {
            return;
        }
        ItemStack display = carried.clone();
        List<Component> lore = new ArrayList<>(display.getData(DataComponentTypes.LORE) == null ? List.of()
                : display.getData(DataComponentTypes.LORE).lines());
        lore.add(Component.empty());
        if (invSlot == CarryService.MELEE_SLOT) {
            lore.add(Text.mm("<muted>近战武器固定在 3 号键，从仓库取出另一把即可替换"));
        } else {
            int inside = invSlot == CarryService.LEGS || invSlot == CarryService.FEET ? packedCount(invSlot) : 0;
            lore.add(Text.mm(inside > 0 ? "<ok>点击 存入仓库 <muted>· 连同里面的 " + inside + " 件" : "<ok>点击 存入仓库"));
        }
        display.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        button(slot, display, e -> stashCarried(invSlot));
    }

    /** Stacks in the cells of the worn rig ({@link CarryService#LEGS}) or backpack. */
    private int packedCount(int armorSlot) {
        int start = armorSlot == CarryService.LEGS ? CarryService.RIG_START : CarryService.PACK_START;
        int cap = armorSlot == CarryService.LEGS ? plugin.carry().rigCapacity(viewer) : plugin.carry().backpackCapacity(viewer);
        int n = 0;
        for (int i = 0; i < cap; i++) {
            ItemStack cell = viewer.getInventory().getItem(start + i);
            if (cell != null && !cell.isEmpty() && !ItemFactory.isUi(cell)) {
                n++;
            }
        }
        return n;
    }

    /**
     * A click on the loadout panel: the item goes into the stash, a worn rig or backpack with
     * everything packed in it (the way unequipping packs it), so nothing is left without a cell.
     */
    private void stashCarried(int invSlot) {
        if (invSlot == CarryService.MELEE_SLOT) {
            Notify.error(viewer, "近战武器固定在 3 号键");
            return;
        }
        plugin.weapons().flush(viewer);
        ItemStack current = viewer.getInventory().getItem(invSlot);
        if (current == null || current.isEmpty() || ItemFactory.isUi(current)) {
            redraw();
            return;
        }
        String why;
        if (invSlot == CarryService.LEGS || invSlot == CarryService.FEET) {
            if (ItemFactory.isRange(current)) {
                Notify.error(viewer, "靶场装备离开靶场时自动收回");
                return;
            }
            ItemStack packed = plugin.carry().unequip(viewer, invSlot);
            if (packed == null) {
                redraw();
                return;
            }
            why = plugin.menus().stash(viewer, packed);
            if (why != null) {
                plugin.carry().equip(viewer, packed);
            }
        } else {
            why = plugin.menus().stash(viewer, current);
            if (why == null) {
                viewer.getInventory().setItem(invSlot, null);
            }
        }
        if (why != null) {
            Notify.error(viewer, why);
            redraw();
            return;
        }
        sellArmedUntil = -1; // the deposit may top up a picked stack
        viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", 0.5f, 0.8f);
        plugin.lobby().captureLoadout(viewer);
        redraw();
    }

    /**
     * Taking out a helmet, vest, rig or backpack that has no room on the player: the one worn in its
     * slot, if any, goes into the stash (packed, for a rig or backpack) and this one goes on. Returns false,
     * having said why, when the worn one cannot go into the stash.
     */
    private boolean swapWorn(ItemStack stack, int slot) {
        ItemStack worn = viewer.getInventory().getItem(slot);
        boolean container = slot == CarryService.LEGS || slot == CarryService.FEET;
        if (ItemFactory.isRange(worn)) {
            Notify.error(viewer, "请先卸下当前的装备");
            return false;
        }
        ItemStack off = worn == null || worn.isEmpty() ? null : container ? plugin.carry().unequip(viewer, slot) : worn;
        String why = off == null ? null : plugin.menus().stash(viewer, off);
        if (why != null) {
            if (container) {
                plugin.carry().equip(viewer, off);
            }
            Notify.error(viewer, "身上没有放它的位置，换下的也存不进仓库：" + why);
            return false;
        }
        if (container) {
            plugin.carry().equip(viewer, stack);
        } else {
            viewer.getInventory().setItem(slot, stack);
        }
        return true;
    }

    /** First click arms the sale for five seconds; the second sells exactly what is picked. */
    private void clickSell() {
        if (selected.isEmpty()) {
            Notify.error(viewer, "先 Shift+点击 选中要出售的物品");
            return;
        }
        if (Bukkit.getCurrentTick() >= sellArmedUntil) {
            sellArmedUntil = Bukkit.getCurrentTick() + confirmTicks();
            redraw();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (sellArmedUntil > 0 && Bukkit.getCurrentTick() >= sellArmedUntil
                        && viewer.getOpenInventory().getTopInventory().getHolder() == this) {
                    sellArmedUntil = -1;
                    redraw();
                }
            }, confirmTicks() + 1L);
            return;
        }
        sellArmedUntil = -1;
        Profile p = profile();
        long earned = 0;
        int sold = 0;
        for (ItemRecord record : new ArrayList<>(selected)) {
            int index = indexOf(p, record);
            if (index < 0) {
                continue;
            }
            p.stash.remove(index);
            earned += plugin.traders().sell(viewer, record);
            sold++;
        }
        selected.clear();
        plugin.profiles().markDirty(viewer.getUniqueId());
        redraw();
        Notify.ok(viewer, "已出售 " + sold + " 件，获得 <accent>◈" + Text.money(earned));
    }

    @Override
    protected void onBottomClick(InventoryClickEvent event) {
        ItemStack stack = event.getCurrentItem();
        if (stack == null || stack.isEmpty() || ItemFactory.isUi(stack) || event.getClickedInventory() == null) {
            return;
        }
        plugin.weapons().flush(viewer);
        // A deposit tops up stacks already in the stash, picked ones too: an armed sale would then sell
        // more than the player confirmed, so it has to be armed again with the new price showing.
        sellArmedUntil = -1;
        Profile p = profile();
        String id = ItemFactory.idOf(stack);
        List<Integer> slots = new ArrayList<>();
        if (event.isShiftClick() && id != null) {
            for (int i = 0; i < 36; i++) {
                if (id.equals(ItemFactory.idOf(viewer.getInventory().getItem(i)))) {
                    slots.add(i);
                }
            }
        } else {
            slots.add(event.getSlot());
        }
        for (int slot : slots) {
            ItemStack current = event.getClickedInventory().getItem(slot);
            if (ItemFactory.isRange(current)) {
                Notify.error(viewer, "靶场武器不能存入仓库");
                continue;
            }
            if (ItemFactory.isTest(current)) {
                Notify.error(viewer, "试验场物资不能存入仓库");
                continue;
            }
            ItemRecord record = plugin.items().toRecord(current);
            if (record == null) {
                continue;
            }
            if (p.stashUsed() + record.footprint() > p.stashCapacity) {
                Notify.error(viewer, "仓库已满，可在仓库中扩容");
                break;
            }
            deposit(plugin, p, record);
            event.getClickedInventory().setItem(slot, null);
        }
        viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", 0.5f, 0.8f);
        plugin.lobby().captureLoadout(viewer);
        plugin.profiles().markDirty(viewer.getUniqueId());
        redraw();
    }

    /** Puts a record into the stash, topping up stacks it can join first. */
    static void deposit(CraftovPlugin plugin, Profile p, ItemRecord record) {
        ItemDef def = plugin.registry().get(record.id);
        if (def == null) {
            p.stash.add(record); // an id items.yml does not have: kept whole, its stack size is unknown
            return;
        }
        int max = def.maxStack();
        for (ItemRecord existing : p.stash) {
            if (record.amount <= 0) {
                return;
            }
            if (existing.stacksWith(record) && existing.amount < max) {
                int moved = Math.min(max - existing.amount, record.amount);
                existing.amount += moved;
                record.amount -= moved;
            }
        }
        // What is left is stored one full stack per record, never above the stack size.
        while (record.amount > max) {
            ItemRecord part = record.copy();
            part.amount = max;
            record.amount -= max;
            p.stash.add(part);
        }
        if (record.amount > 0) {
            p.stash.add(record);
        }
    }

    void reopen() {
        open();
    }
}
