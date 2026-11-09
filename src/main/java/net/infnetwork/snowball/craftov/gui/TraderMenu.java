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
        super(viewer, 6, Text.mm("<#F2E6C8>商人 · 军需处"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        Profile p = plugin.profiles().get(viewer);
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
                    lore.add(Text.mm("<#5BD16B>左键 购买 30 发 <#F2C94C>◈" + Text.money(price * 30)));
                    lore.add(Text.mm("<#5BD16B>右键 购买 " + def.maxStack() + " 发 <#F2C94C>◈" + Text.money(price * def.maxStack())));
                } else {
                    lore.add(Text.mm("<#5BD16B>左键 购买 <#F2C94C>◈" + Text.money(price)));
                    if (def.maxStack() > 1) {
                        lore.add(Text.mm("<#5BD16B>右键 购买 " + def.maxStack() + " 个 <#F2C94C>◈" + Text.money(price * def.maxStack())));
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
            button(slot++, UiItems.button("craftov:ui/tab_" + t.name().toLowerCase(), (selected ? "<#F2C94C>▶ " : "<white>") + t.label,
                    List.of("<#8A8F98>块币 ◈" + Text.money(p.money), "<#8A8F98>潜行点击下方背包物品可直接出售")), e -> {
                tab = t;
                redraw();
            });
        }
    }

    private void drawServices(Profile p) {
        int tier = p.secureTier;
        boolean max = tier >= 4;
        button(10, UiItems.button("craftov:ui/icon_secure", "<#B66BFF><b>升级安全箱", List.of(
                "<#8A8F98>当前 " + plugin.traders().secureSlots(p) + " 格",
                max ? "<#5BD16B>已满级" : "<#8A8F98>升级至 " + TraderService.SECURE_SLOTS[tier + 1] + " 格 · ◈" + Text.money(TraderService.SECURE_PRICES[tier + 1]))),
                e -> {
                    plugin.traders().upgradeSecure(viewer);
                    redraw();
                });
        button(12, UiItems.button("craftov:ui/icon_upgrade", "<white><b>仓库扩容 +30", List.of("<#8A8F98>当前 " + p.stashCapacity + " 格",
                "<#8A8F98>价格 ◈" + Text.money(plugin.traders().stashUpgradePrice(p)))), e -> {
            plugin.traders().upgradeStash(viewer);
            redraw();
        });
        button(14, UiItems.button("craftov:ui/icon_repair", "<white><b>维修全部护甲", List.of("<#8A8F98>修复身上与背包内护甲的耐久")), e -> {
            plugin.traders().repairAll(viewer);
            redraw();
        });
        button(16, UiItems.button("craftov:ui/icon_ammo", "<white><b>补充弹药", List.of("<#8A8F98>为身上每把枪补足 3 个弹匣", "<#8A8F98>（使用当前装填的弹种）")), e -> {
            refill();
            redraw();
        });
        button(22, UiItems.button("craftov:ui/icon_sell", "<#FFC23D><b>一键出售物资", List.of("<#8A8F98>出售背包内所有“物资”类物品",
                "<#8A8F98>（不包括收藏品、装备、武器、医疗品）")), e -> {
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
            total += plugin.traders().sell(viewer, record);
            count += stack.getAmount();
            viewer.getInventory().setItem(slot, null);
        }
        plugin.lobby().captureLoadout(viewer);
        viewer.sendActionBar(Text.mm(count == 0 ? "<#9AA0A6>没有可出售的物资" : "<#FFC23D>出售 " + count + " 件物资，获得 ◈" + Text.money(total)));
    }

    @Override
    protected void onBottomClick(InventoryClickEvent event) {
        ItemStack stack = event.getCurrentItem();
        if (stack == null || stack.isEmpty() || ItemFactory.isUi(stack) || event.getClickedInventory() == null) {
            return;
        }
        plugin.weapons().flush(viewer);
        if (ItemFactory.isRange(event.getClickedInventory().getItem(event.getSlot()))) {
            viewer.sendActionBar(Text.mm("<#FF8A80>靶场武器不能出售"));
            return;
        }
        ItemRecord record = plugin.items().toRecord(event.getClickedInventory().getItem(event.getSlot()));
        if (record == null) {
            return;
        }
        long price = plugin.traders().sellPrice(record);
        if (!event.isShiftClick()) {
            viewer.sendActionBar(Text.mm("<#9AA0A6>商人收购价 <#F2C94C>◈" + Text.money(price) + " <#9AA0A6>· 潜行点击出售"));
            return;
        }
        event.getClickedInventory().setItem(event.getSlot(), null);
        plugin.traders().sell(viewer, record);
        plugin.lobby().captureLoadout(viewer);
        viewer.sendActionBar(Text.mm("<#FFC23D>已出售，获得 ◈" + Text.money(price)));
        redraw();
    }
}
