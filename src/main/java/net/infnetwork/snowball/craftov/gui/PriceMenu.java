package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Choose a listing price for a stash item. */
final class PriceMenu extends Menu {

    private final CraftovPlugin plugin;
    private final int stashIndex;
    private final ItemRecord record;
    private final StashMenu back;
    /** Fixed anchor price (MarketService.anchorFor): the default price, and what players judge listings by. */
    private final long reference;
    /** Highest price the market accepts for this record (MarketService.maxPrice). */
    private final long cap;
    /** Most a system buyer pays for this record, 0 when they never buy it (MarketService.virtualPrice). */
    private final long virtual;
    private long price;

    PriceMenu(CraftovPlugin plugin, Player viewer, int stashIndex, ItemRecord record, StashMenu back) {
        super(viewer, 3, Text.mm("<#F2E6C8>交易行 · 定价"));
        this.plugin = plugin;
        this.stashIndex = stashIndex;
        this.record = record;
        this.back = back;
        this.reference = plugin.market().anchorFor(record);
        this.cap = plugin.market().maxPrice(record);
        this.virtual = plugin.market().virtualPrice(record);
        this.price = reference;
    }

    @Override
    protected void draw() {
        ItemStack display = plugin.items().fromRecord(record);
        if (display != null) {
            set(4, display);
        }
        double ratio = price / (double) reference;
        double chance = plugin.market().virtualChance(record, price);
        String hint = virtual <= 0 ? "<#FF4D4D>系统买家不会收购<#8A8F98>（" + plugin.market().virtualRarityNote() + "<#8A8F98>）"
                : chance <= 0 ? "<#FF4D4D>高于系统收购价 ◈" + Text.money(virtual) + "，系统买家不会收购，只能等其他玩家"
                : plugin.market().virtualCapped(viewer.getUniqueId(), record.id) ? "<#FFC23D>今天系统买家已收满你的这种物品，明天再来"
                : chance >= 0.3 ? "<#5BD16B>系统买家很快收购" : chance >= 0.12 ? "<#5BD16B>系统买家较快收购"
                : "<#FFC23D>系统买家可能要等几分钟";
        long fee = Math.round(price * plugin.getConfig().getDouble("market.listing-fee", 0.02));
        long tax = Math.round(price * plugin.getConfig().getDouble("market.sale-tax", 0.05));
        adjust(10, -0.10, "-10%");
        adjust(11, -0.01, "-1%");
        List<String> info = new ArrayList<>(List.of("<#8A8F98>参考价 ◈" + Text.money(reference) + " (" + Math.round(ratio * 100) + "%)",
                "<#8A8F98>预计 " + hint, "<#8A8F98>上架手续费 ◈" + Text.money(fee) + " · 成交税 ◈" + Text.money(tax)));
        int level = plugin.market().minBuyLevel();
        if (plugin.profiles().get(viewer).level < level) {
            info.add("<#FFC23D>账号等级 " + level + " 前只有系统买家会收购");
        }
        if (plugin.market().traded(record.id)) {
            info.add("<#8A8F98>玩家成交均价 ◈" + Text.money(plugin.market().referenceFor(record)));
        }
        info.add("<#8A8F98>近期成交 " + history() + " <#5F6368>(单件)");
        info.add(price >= cap ? "<#FFC23D>已达最高售价（参考价 " + Text.decimal(plugin.market().maxPriceMultiplier()) + " 倍）"
                : "<#8A8F98>最高售价 ◈" + Text.money(cap));
        set(13, UiItems.button("craftov:ui/icon_value", "<#F2C94C>售价 ◈" + Text.money(price), info));
        adjust(15, 0.01, "+1%");
        adjust(16, 0.10, "+10%");
        button(18, UiItems.button("craftov:ui/icon_cancel", "<#FF8A80>返回", List.of()), e -> back.reopen());
        if (virtual > 0) {
            button(20, UiItems.button("craftov:ui/icon_value", "<white>系统收购价",
                    List.of("<#8A8F98>系统买家最多出 ◈" + Text.money(virtual) + "（物品价值的 "
                            + Math.round(plugin.market().virtualMaxValue() * 100) + "%）", "<#8A8F98>定得越低卖得越快")),
                    e -> setPrice(virtual));
        } else {
            set(20, UiItems.button("craftov:ui/icon_value", "<#8A8F98>系统收购价",
                    List.of("<#FF8A80>" + plugin.market().virtualRarityNote())));
        }
        button(21, UiItems.button("craftov:ui/icon_value", "<white>参考价 ×0.9", List.of()), e -> setPrice(Math.round(reference * 0.9)));
        button(22, UiItems.button("craftov:ui/icon_value", "<white>参考价", List.of()), e -> setPrice(reference));
        button(23, UiItems.button("craftov:ui/icon_value", "<white>参考价 ×1.2", List.of()), e -> setPrice(Math.round(reference * 1.2)));
        button(26, UiItems.button("craftov:ui/icon_start", "<#5BD16B><b>确认上架", List.of("<#9AA0A6>物品将从仓库移至交易行")), e -> {
            if (plugin.market().list(viewer, stashIndex, price)) {
                back.reopen();
            } else {
                viewer.closeInventory();
            }
        });
    }

    private String history() {
        List<Long> h = plugin.market().history(record.id);
        if (h.isEmpty()) {
            return "暂无";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, h.size() - 3); i < h.size(); i++) {
            sb.append("◈").append(Text.moneyShort(h.get(i))).append(' ');
        }
        return sb.toString().trim();
    }

    private void adjust(int slot, double delta, String label) {
        if (delta > 0 && price >= cap) {
            String why = "已达最高售价 ◈" + Text.money(cap) + "（参考价 " + Text.decimal(plugin.market().maxPriceMultiplier()) + " 倍）";
            button(slot, UiItems.button("craftov:ui/arrow_right", "<#8A8F98>" + label, List.of("<#FFC23D>" + why)),
                    e -> viewer.sendActionBar(Text.mm("<#FFC23D>" + why)));
            return;
        }
        // At least one coin per click, so +1% still moves a cheap item towards the cap.
        button(slot, UiItems.button(delta < 0 ? "craftov:ui/arrow_left" : "craftov:ui/arrow_right", "<white>" + label, List.of()), e -> {
            long next = Math.round(price * (1 + delta));
            setPrice(delta < 0 ? Math.min(price - 1, next) : Math.max(price + 1, next));
        });
    }

    /** Sets the price within 1 .. cap; the + buttons stop at the cap. */
    private void setPrice(long value) {
        price = Math.max(1, Math.min(cap, value));
        redraw();
    }
}
