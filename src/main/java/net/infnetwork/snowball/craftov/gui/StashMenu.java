package net.infnetwork.snowball.craftov.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
final class StashMenu extends Menu {

    enum Mode { TAKE, SELL, LIST }

    private final CraftovPlugin plugin;
    private int page;
    private Mode mode = Mode.TAKE;
    private @Nullable Category filter;
    private final List<Integer> visible = new ArrayList<>();

    StashMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 6, "stash", Text.mm("仓库"));
        this.plugin = plugin;
    }

    private Profile profile() {
        return plugin.profiles().get(viewer);
    }

    @Override
    protected void draw() {
        Profile p = profile();
        visible.clear();
        for (int i = 0; i < p.stash.size(); i++) {
            ItemDef def = plugin.registry().get(p.stash.get(i).id);
            if (def != null && (filter == null || def.category() == filter)) {
                visible.add(i);
            }
        }
        int pages = Math.max(1, (visible.size() + 44) / 45);
        page = Math.min(page, pages - 1);
        // Page and filter live in the title, changed in place as the player pages through.
        restyle("stash", Text.mm("仓库 <muted>· " + (page + 1) + "/" + pages + (filter == null ? "" : " · " + filter.displayName())));
        for (int slot = 0; slot < 45; slot++) {
            int index = page * 45 + slot;
            if (index >= visible.size()) {
                break;
            }
            int stashIndex = visible.get(index);
            ItemRecord record = p.stash.get(stashIndex);
            ItemStack display = plugin.items().fromRecord(record);
            if (display == null) {
                continue;
            }
            List<Component> lore = new ArrayList<>(display.getData(DataComponentTypes.LORE) == null ? List.of()
                    : display.getData(DataComponentTypes.LORE).lines());
            lore.add(Component.empty());
            switch (mode) {
                case TAKE -> lore.add(Text.mm("<ok>点击取出到背包"));
                case SELL -> lore.add(Text.mm("<accent>点击出售给商人 <accent>◈" + Text.money(plugin.traders().sellPrice(record))));
                case LIST -> lore.add(Text.mm("<info>点击在交易行上架 <muted>参考价 ◈" + Text.money(plugin.market().anchorFor(record))));
            }
            display.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
            button(slot, display, e -> clickStash(stashIndex, record));
        }
        fillRow(5, UiItems.filler());
        button(45, UiItems.button("craftov:ui/arrow_left", "<white>上一页", List.of("<muted>" + (page + 1) + "/" + pages)), e -> {
            page = Math.max(0, page - 1);
            redraw();
        });
        button(53, UiItems.button("craftov:ui/arrow_right", "<white>下一页", List.of("<muted>" + (page + 1) + "/" + pages)), e -> {
            page = Math.min(pages - 1, page + 1);
            redraw();
        });
        button(46, UiItems.button("craftov:ui/icon_sort", "<white>整理", List.of("<muted>按类别、品质、价值排序并合并堆叠")), e -> {
            sort(p);
            redraw();
        });
        button(47, UiItems.button("craftov:ui/icon_filter", "<white>筛选：" + (filter == null ? "全部" : filter.displayName()),
                List.of("<muted>点击切换类别")), e -> {
            Category[] all = Category.values();
            filter = filter == null ? all[0] : filter.ordinal() + 1 < all.length ? all[filter.ordinal() + 1] : null;
            page = 0;
            redraw();
        });
        String modeName = switch (mode) {
            case TAKE -> "<ok>取出";
            case SELL -> "<accent>出售给商人";
            case LIST -> "<info>交易行上架";
        };
        button(48, UiItems.button("craftov:ui/icon_mode", "<white>模式：" + modeName, List.of("<muted>点击切换：取出 / 出售 / 上架",
                "<muted>点击下方背包中的物品可存入仓库", "<muted>潜行点击可存入同类全部")), e -> {
            mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
            redraw();
        });
        set(49, UiItems.button("craftov:ui/icon_stash", "<text>仓库 " + p.stashUsed() + "/" + p.stashCapacity,
                List.of("<muted>块币 <accent>◈" + Text.money(p.money), "<muted>仓库总价值 <white>◈" + Text.money(stashValue(p)))));
        List<String> mailLore = new ArrayList<>(List.of("<muted>交易行退回、保险理赔、藏身处产物", "<muted>以及大厅里身上放不下的物品",
                "<muted>点击领取到仓库"));
        long nextClaim = net.infnetwork.snowball.craftov.economy.InsuranceService.nextClaim(p);
        if (nextClaim > 0) {
            long minutes = Math.max(1, (nextClaim - System.currentTimeMillis() + 59_999) / 60_000);
            mailLore.add("<accent>理赔途中 " + p.claims.size() + " 件，最早约 " + minutes + " 分钟后到账");
        }
        button(50, UiItems.button("craftov:ui/icon_mail", "<white>邮箱 <accent>" + p.mailbox.size(), mailLore), e -> {
            claimMailbox(p);
            redraw();
        });
        button(51, UiItems.button("craftov:ui/icon_secure", "<info>安全箱", List.of("<muted>打开安全箱")), e -> plugin.menus().openSecure(viewer));
        button(52, UiItems.button("craftov:ui/icon_upgrade", "<white>仓库扩容 +30", List.of("<muted>价格 ◈" + Text.money(plugin.traders().stashUpgradePrice(p)))),
                e -> {
                    plugin.traders().upgradeStash(viewer);
                    redraw();
                });
    }

    private long stashValue(Profile p) {
        long total = 0;
        for (ItemRecord record : p.stash) {
            total += plugin.items().value(record);
        }
        return total;
    }

    private void clickStash(int stashIndex, ItemRecord record) {
        Profile p = profile();
        if (stashIndex >= p.stash.size() || p.stash.get(stashIndex) != record) {
            redraw();
            return;
        }
        switch (mode) {
            case TAKE -> {
                ItemStack stack = plugin.items().fromRecord(record);
                if (stack == null) {
                    return;
                }
                ItemDef def = plugin.items().defOf(stack);
                if (ItemFactory.hasContents(stack) && def != null && def.isContainer()) {
                    int slot = CarryService.containerSlot(def.category());
                    ItemStack equipped = viewer.getInventory().getItem(slot);
                    if (equipped != null && !equipped.isEmpty()) {
                        viewer.sendActionBar(Text.mm("<danger>请先卸下当前的" + def.category().displayName()));
                        return;
                    }
                    p.stash.remove(stashIndex);
                    plugin.carry().equip(viewer, stack);
                } else {
                    ItemStack left = plugin.carry().give(viewer, stack, false);
                    int moved = stack.getAmount() - (left == null ? 0 : left.getAmount());
                    if (moved <= 0) {
                        viewer.sendActionBar(Text.mm("<danger>背包空间不足"));
                        return;
                    }
                    // Only what was handed over leaves the record: a record above the stack size
                    // (older builds stored those) builds a single stack and keeps the rest.
                    record.amount -= moved;
                    if (record.amount <= 0) {
                        p.stash.remove(stashIndex);
                    }
                }
                viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", 0.6f, 1f);
            }
            case SELL -> {
                p.stash.remove(stashIndex);
                long price = plugin.traders().sell(viewer, record);
                viewer.sendActionBar(Text.mm("<accent>已出售，获得 ◈" + Text.money(price)));
            }
            case LIST -> {
                if (record.isKit()) {
                    viewer.sendActionBar(Text.mm("<danger>免费补给的物品不能上架交易行"));
                    return;
                }
                if (!plugin.market().refuseIfClosed(viewer)) {
                    new PriceMenu(plugin, viewer, stashIndex, record, this).open();
                }
                return;
            }
        }
        plugin.lobby().captureLoadout(viewer);
        plugin.profiles().markDirty(viewer.getUniqueId());
        redraw();
    }

    @Override
    protected void onBottomClick(InventoryClickEvent event) {
        ItemStack stack = event.getCurrentItem();
        if (stack == null || stack.isEmpty() || ItemFactory.isUi(stack) || event.getClickedInventory() == null) {
            return;
        }
        plugin.weapons().flush(viewer);
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
                viewer.sendActionBar(Text.mm("<danger>靶场武器不能存入仓库"));
                continue;
            }
            if (ItemFactory.isTest(current)) {
                viewer.sendActionBar(Text.mm("<danger>试验场物资不能存入仓库"));
                continue;
            }
            ItemRecord record = plugin.items().toRecord(current);
            if (record == null) {
                continue;
            }
            if (p.stashUsed() + record.footprint() > p.stashCapacity) {
                viewer.sendActionBar(Text.mm("<danger>仓库已满，可在仓库中扩容"));
                break;
            }
            deposit(p, record);
            event.getClickedInventory().setItem(slot, null);
        }
        viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", 0.5f, 0.8f);
        plugin.lobby().captureLoadout(viewer);
        plugin.profiles().markDirty(viewer.getUniqueId());
        redraw();
    }

    private void deposit(Profile p, ItemRecord record) {
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

    private void sort(Profile p) {
        List<ItemRecord> merged = new ArrayList<>();
        for (ItemRecord record : p.stash) {
            Profile tmp = new Profile();
            tmp.stash = merged;
            deposit(tmp, record.copy());
        }
        merged.sort(Comparator.<ItemRecord>comparingInt(r -> {
            ItemDef d = plugin.registry().get(r.id);
            return d == null ? 99 : d.category().ordinal();
        }).thenComparing(r -> {
            ItemDef d = plugin.registry().get(r.id);
            return d == null ? 0 : -d.rarity().ordinal();
        }).thenComparingLong(r -> -plugin.items().value(r)));
        p.stash = merged;
        plugin.profiles().markDirty(viewer.getUniqueId());
    }

    private void claimMailbox(Profile p) {
        int claimed = 0;
        while (!p.mailbox.isEmpty()) {
            ItemRecord record = p.mailbox.get(0);
            if (p.stashUsed() + record.footprint() > p.stashCapacity) {
                viewer.sendActionBar(Text.mm("<danger>仓库空间不足"));
                break;
            }
            p.mailbox.remove(0);
            deposit(p, record);
            claimed++;
        }
        if (claimed > 0) {
            viewer.sendActionBar(Text.mm("<ok>已领取 " + claimed + " 件物品"));
        }
        plugin.profiles().markDirty(viewer.getUniqueId());
    }

    void reopen() {
        open();
    }
}
