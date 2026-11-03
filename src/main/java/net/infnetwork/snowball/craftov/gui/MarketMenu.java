package net.infnetwork.snowball.craftov.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.economy.MarketService;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
final class MarketMenu extends Menu {

    private enum View { BROWSE, ITEM, MINE }

    private static final Category[] TABS = {null, Category.WEAPON, Category.AMMO, Category.ARMOR, Category.HELMET, Category.MEDICAL,
            Category.VALUABLE, Category.COLLECTIBLE};

    private final CraftovPlugin plugin;
    private View view = View.BROWSE;
    private @Nullable Category category;
    private @Nullable String item;

    MarketMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<#F2E6C8>交易行"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        switch (view) {
            case BROWSE -> browse();
            case ITEM -> listings();
            case MINE -> mine();
        }
        fillRow(5, UiItems.filler());
        int slot = 45;
        for (Category c : TABS) {
            boolean selected = view == View.BROWSE && c == category;
            String label = c == null ? "全部" : c.displayName();
            button(slot++, UiItems.button("craftov:ui/tab_market", (selected ? "<#F2C94C>▶ " : "<white>") + label, List.of()), e -> {
                view = View.BROWSE;
                category = c;
                redraw();
            });
        }
        button(53, UiItems.button("craftov:ui/icon_mail", (view == View.MINE ? "<#F2C94C>▶ " : "<white>") + "我的上架",
                List.of("<#8A8F98>在仓库切换到“上架”模式即可寄售", "<#8A8F98>块币 ◈" + Text.money(plugin.profiles().get(viewer).money))), e -> {
            view = View.MINE;
            redraw();
        });
    }

    private void browse() {
        int slot = 0;
        for (MarketService.Offer offer : plugin.market().offers(category)) {
            if (slot >= 45) {
                break;
            }
            ItemDef def = plugin.registry().get(offer.itemId());
            if (def == null) {
                continue;
            }
            ItemStack display = plugin.items().create(def, 1);
            List<Component> lore = new ArrayList<>(display.getData(DataComponentTypes.LORE).lines());
            lore.add(Component.empty());
            lore.add(Text.mm("<#8A8F98>最低单价 <#F2C94C>◈" + Text.money(offer.lowest()) + " <#8A8F98>· 在售 " + offer.count() + " 件"));
            lore.add(Text.mm("<#8A8F98>参考价 ◈" + Text.money(Math.round(offer.reference()))));
            lore.add(Text.mm("<#5BD16B>点击查看在售列表"));
            display.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
            button(slot++, display, e -> {
                view = View.ITEM;
                item = def.id();
                redraw();
            });
        }
        if (slot == 0) {
            set(22, UiItems.button("craftov:ui/icon_market", "<#9AA0A6>暂时没有在售物品", List.of("<#8A8F98>去仓库切换“上架”模式，成为第一个卖家吧")));
        }
    }

    private void listings() {
        int slot = 0;
        for (MarketService.Listing listing : plugin.market().listingsFor(item)) {
            if (slot >= 45) {
                break;
            }
            ItemStack display = plugin.items().fromRecord(listing.item);
            if (display == null) {
                continue;
            }
            List<Component> lore = new ArrayList<>(display.getData(DataComponentTypes.LORE).lines());
            lore.add(Component.empty());
            lore.add(Text.mm("<#8A8F98>卖家 <white>" + Text.escape(listing.sellerName)));
            lore.add(Text.mm("<#8A8F98>价格 <#F2C94C>◈" + Text.money(listing.price)));
            lore.add(Text.mm("<#5BD16B>点击购买（存入仓库）"));
            display.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
            String id = listing.id;
            button(slot++, display, e -> {
                plugin.market().buy(viewer, id);
                redraw();
            });
        }
        if (slot == 0) {
            view = View.BROWSE;
            browse();
        }
    }

    private void mine() {
        int slot = 0;
        for (MarketService.Listing listing : plugin.market().mine(viewer.getUniqueId())) {
            ItemStack display = plugin.items().fromRecord(listing.item);
            if (display == null) {
                continue;
            }
            List<Component> lore = new ArrayList<>(display.getData(DataComponentTypes.LORE).lines());
            lore.add(Component.empty());
            lore.add(Text.mm("<#8A8F98>售价 <#F2C94C>◈" + Text.money(listing.price)));
            long hours = Math.max(0, (listing.expires - System.currentTimeMillis()) / 3_600_000L);
            lore.add(Text.mm("<#8A8F98>剩余 " + hours + " 小时"));
            lore.add(Text.mm("<#FF8A80>点击下架（退回邮箱）"));
            display.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
            String id = listing.id;
            button(slot++, display, e -> {
                plugin.market().cancel(viewer, id);
                redraw();
            });
        }
        if (slot == 0) {
            set(22, UiItems.button("craftov:ui/icon_mail", "<#9AA0A6>你还没有上架物品", List.of()));
        }
    }
}
