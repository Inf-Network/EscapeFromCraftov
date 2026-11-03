package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Choose a listing price for a stash item. */
final class PriceMenu extends Menu {

    private final CraftovPlugin plugin;
    private final int stashIndex;
    private final ItemRecord record;
    private final StashMenu back;
    private final long reference;
    private long price;

    PriceMenu(CraftovPlugin plugin, Player viewer, int stashIndex, ItemRecord record, StashMenu back) {
        super(viewer, 3, Text.mm("<#F2E6C8>交易行 · 定价"));
        this.plugin = plugin;
        this.stashIndex = stashIndex;
        this.record = record;
        this.back = back;
        this.reference = Math.max(1, plugin.market().referenceFor(record));
        this.price = reference;
    }

    @Override
    protected void draw() {
        ItemStack display = plugin.items().fromRecord(record);
        if (display != null) {
            set(4, display);
        }
        double ratio = price / (double) reference;
        String hint = ratio <= 0.9 ? "<#5BD16B>很快售出" : ratio <= 1.0 ? "<#5BD16B>较快售出" : ratio <= 1.1 ? "<#FFC23D>可能需要等待"
                : ratio <= 1.25 ? "<#FF8A80>较难售出" : "<#FF4D4D>系统买家不会收购";
        long fee = Math.round(price * plugin.getConfig().getDouble("market.listing-fee", 0.02));
        long tax = Math.round(price * plugin.getConfig().getDouble("market.sale-tax", 0.05));
        adjust(10, -0.10, "-10%");
        adjust(11, -0.01, "-1%");
        set(13, UiItems.button("craftov:ui/icon_value", "<#F2C94C>售价 ◈" + Text.money(price),
                List.of("<#8A8F98>参考价 ◈" + Text.money(reference) + " (" + Math.round(ratio * 100) + "%)", "<#8A8F98>预计 " + hint,
                        "<#8A8F98>上架手续费 ◈" + Text.money(fee) + " · 成交税 ◈" + Text.money(tax),
                        "<#8A8F98>近期成交 " + history())));
        adjust(15, 0.01, "+1%");
        adjust(16, 0.10, "+10%");
        button(18, UiItems.button("craftov:ui/icon_cancel", "<#FF8A80>返回", List.of()), e -> back.reopen());
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
        button(slot, UiItems.button(delta < 0 ? "craftov:ui/arrow_left" : "craftov:ui/arrow_right", "<white>" + label, List.of()),
                e -> setPrice(Math.max(1, Math.round(price * (1 + delta)))));
    }

    private void setPrice(long value) {
        price = Math.max(1, value);
        redraw();
    }
}
