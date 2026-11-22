package net.infnetwork.snowball.craftov.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.economy.TraderService;
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

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
final class TraderMenu extends Menu {

    private final CraftovPlugin plugin;
    private TraderService.Tab tab = TraderService.Tab.WEAPON;

    TraderMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 6, "trader", Text.mm("商人 · 军需处"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        Profile p = plugin.profiles().get(viewer);
        restyle("trader", Text.mm("商人 · 军需处 <muted>· " + tab.label));
        if (tab == TraderService.Tab.SERVICE) {
            drawServices(p);
        } else {
            int slot = 0;
            for (ItemDef def : plugin.traders().catalog(tab)) {
                if (slot >= 45) {
                    break;
                }
                ItemStack display = plugin.items().create(def, 1);
                long price = plugin.traders().buyPrice(def);
                List<Component> lore = new ArrayList<>(display.getData(DataComponentTypes.LORE).lines());
                lore.add(Component.empty());
                if (def.category() == Category.AMMO) {
                    lore.add(Text.mm("<ok>左键 购买 30 发 <accent>◈" + Text.money(price * 30)));
                    lore.add(Text.mm("<ok>右键 购买 " + def.maxStack() + " 发 <accent>◈" + Text.money(price * def.maxStack())));
                } else {
                    lore.add(Text.mm("<ok>左键 购买 <accent>◈" + Text.money(price)));
                    if (def.maxStack() > 1) {
                        lore.add(Text.mm("<ok>右键 购买 " + def.maxStack() + " 个 <accent>◈" + Text.money(price * def.maxStack())));
                    }
                }
                display.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
                button(slot++, display, e -> {
                    int amount = def.category() == Category.AMMO ? (e.isRightClick() ? def.maxStack() : 30)
                            : e.isRightClick() ? def.maxStack() : 1;
                    plugin.traders().buy(viewer, def, amount);
                    redraw();
                });
            }
        }
        fillRow(5, UiItems.filler());
        int slot = 45;
        for (TraderService.Tab t : TraderService.Tab.values()) {
            boolean selected = t == tab;
            button(slot++, UiItems.button("craftov:ui/tab_" + t.name().toLowerCase(), (selected ? "<accent>▶ " : "<white>") + t.label,
                    List.of("<muted>块币 ◈" + Text.money(p.money), "<muted>潜行点击下方背包物品可直接出售")), e -> {
                tab = t;
                redraw();
            });
        }
    }

    private void drawServices(Profile p) {
        int tier = p.secureTier;
        boolean max = tier >= 4;
        button(10, UiItems.button("craftov:ui/icon_secure", "<info><b>升级安全箱", List.of(
                "<muted>当前 " + plugin.traders().secureSlots(p) + " 格",
                max ? "<ok>已满级" : "<muted>升级至 " + TraderService.SECURE_SLOTS[tier + 1] + " 格 · ◈" + Text.money(TraderService.SECURE_PRICES[tier + 1]))),
                e -> {
                    plugin.traders().upgradeSecure(viewer);
                    redraw();
                });
        button(12, UiItems.button("craftov:ui/icon_upgrade", "<white><b>仓库扩容 +30", List.of("<muted>当前 " + p.stashCapacity + " 格",
                "<muted>价格 ◈" + Text.money(plugin.traders().stashUpgradePrice(p)))), e -> {
            plugin.traders().upgradeStash(viewer);
            redraw();
        });
        button(14, UiItems.button("craftov:ui/icon_repair", "<white><b>维修全部护甲", List.of("<muted>修复身上与背包内护甲的耐久")), e -> {
            plugin.traders().repairAll(viewer);
            redraw();
        });
        button(16, UiItems.button("craftov:ui/icon_ammo", "<white><b>补充弹药", List.of("<muted>为身上每把枪补足 3 个弹匣", "<muted>（使用当前装填的弹种）")), e -> {
            refill();
            redraw();
        });
        button(22, UiItems.button("craftov:ui/icon_sell", "<accent><b>一键出售物资", List.of("<muted>出售背包内所有“物资”类物品",
                "<muted>（不包括收藏品、装备、武器、医疗品）")), e -> {
            sellValuables();
            redraw();
        });
    }

    private void refill() {
        plugin.weapons().flush(viewer);
        for (ItemStack stack : viewer.getInventory().getContents()) {
            ItemDef def = plugin.items().defOf(stack);
            if (def == null || !def.isGun()) {
                continue;
            }
            String ammoId = ItemFactory.ammoType(stack);
            if (ammoId == null) {
                List<ItemDef> options = plugin.registry().ammoFor(def.gun().caliber());
                if (options.isEmpty()) {
                    continue;
                }
                ammoId = options.get(0).id();
            }
            ItemDef ammo = plugin.registry().get(ammoId);
            if (ammo == null) {
                continue;
            }
            int have = plugin.weapons().reserve(viewer, def.gun(), ammoId);
            int need = def.gun().magazine() * 3 - have;
            if (need > 0) {
                plugin.traders().buy(viewer, ammo, need);
            }
        }
    }

    private void sellValuables() {
        long total = 0;
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = viewer.getInventory().getItem(slot);
            ItemDef def = plugin.items().defOf(stack);
            if (def == null || def.category() != Category.VALUABLE) {
                continue;
            }
            ItemRecord record = plugin.items().toRecord(stack);
            if (record == null) {
                continue; // range loaners and test items are not for sale
            }
            total += plugin.traders().sell(viewer, record);
            count += stack.getAmount();
            viewer.getInventory().setItem(slot, null);
        }
        plugin.lobby().captureLoadout(viewer);
        viewer.sendActionBar(Text.mm(count == 0 ? "<muted>没有可出售的物资" : "<accent>出售 " + count + " 件物资，获得 ◈" + Text.money(total)));
    }

    @Override
    protected void onBottomClick(InventoryClickEvent event) {
        ItemStack stack = event.getCurrentItem();
        if (stack == null || stack.isEmpty() || ItemFactory.isUi(stack) || event.getClickedInventory() == null) {
            return;
        }
        plugin.weapons().flush(viewer);
        if (ItemFactory.isRange(event.getClickedInventory().getItem(event.getSlot()))) {
            viewer.sendActionBar(Text.mm("<danger>靶场武器不能出售"));
            return;
        }
        if (ItemFactory.isTest(event.getClickedInventory().getItem(event.getSlot()))) {
            viewer.sendActionBar(Text.mm("<danger>试验场物资不能出售"));
            return;
        }
        ItemRecord record = plugin.items().toRecord(event.getClickedInventory().getItem(event.getSlot()));
        if (record == null) {
            return;
        }
        if (plugin.registry().get(record.id) == null) {
            viewer.sendActionBar(Text.mm("<danger>物品表里暂时没有这件物品，不能出售"));
            return;
        }
        long price = plugin.traders().sellPrice(record);
        if (!event.isShiftClick()) {
            viewer.sendActionBar(Text.mm("<muted>商人收购价 <accent>◈" + Text.money(price) + " <muted>· 潜行点击出售"));
            return;
        }
        event.getClickedInventory().setItem(event.getSlot(), null);
        plugin.traders().sell(viewer, record);
        plugin.lobby().captureLoadout(viewer);
        viewer.sendActionBar(Text.mm("<accent>已出售，获得 ◈" + Text.money(price)));
        redraw();
    }
}
