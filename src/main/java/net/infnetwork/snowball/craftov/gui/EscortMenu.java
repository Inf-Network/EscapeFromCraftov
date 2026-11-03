package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.escort.EscortService;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

final class EscortMenu extends Menu {

    private static final long[] REWARDS = {20_000, 50_000, 100_000, 250_000};
    private final CraftovPlugin plugin;

    EscortMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 5, Text.mm("<#F2E6C8>护航大厅"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        int slot = 0;
        for (EscortService.Contract c : plugin.escorts().all()) {
            if (slot >= 27) {
                break;
            }
            StringBuilder escorts = new StringBuilder();
            for (UUID uuid : c.escorts) {
                Player p = Bukkit.getPlayer(uuid);
                escorts.append(p == null ? "?" : p.getName()).append(' ');
            }
            List<String> lore = List.of("<#8A8F98>委托人 <white>" + Text.escape(c.clientName),
                    "<#8A8F98>赏金 <#F2C94C>◈" + Text.money(c.reward),
                    "<#8A8F98>护航 " + c.escorts.size() + "/2 <white>" + Text.escape(escorts.toString().trim()),
                    "<#8A8F98>状态 <white>" + switch (c.state) {
                        case OPEN -> "等待护航";
                        case ACCEPTED -> "已接取";
                        case IN_RAID -> "行动中";
                    },
                    c.client.equals(viewer.getUniqueId()) ? "<#FF8A80>点击取消委托（退还赏金）" : "<#5BD16B>点击接取，加入委托人小队");
            button(slot++, UiItems.button("craftov:ui/icon_escort", "<#7FE0D0><b>护航委托 #" + c.id, lore), e -> {
                if (c.client.equals(viewer.getUniqueId())) {
                    plugin.escorts().cancel(viewer);
                } else {
                    plugin.escorts().accept(viewer, c.id);
                }
                redraw();
            });
        }
        if (slot == 0) {
            set(13, UiItems.button("craftov:ui/icon_escort", "<#9AA0A6>暂时没有护航委托", List.of("<#8A8F98>发布委托，让高手护送你撤离")));
        }
        fillRow(3, UiItems.filler());
        set(36, UiItems.button("craftov:ui/icon_help", "<white>护航规则", List.of(
                "<#9AA0A6>委托人托管赏金，护航者加入其小队一同出击。",
                "<#9AA0A6>委托人成功撤离：护航者平分全部赏金。",
                "<#9AA0A6>委托人阵亡：护航者获得 30%，其余退回。")));
        int s = 38;
        for (long reward : REWARDS) {
            button(s++, UiItems.button("craftov:ui/icon_value", "<#F2C94C>发布委托 ◈" + Text.money(reward), List.of("<#9AA0A6>赏金立即托管")), e -> {
                plugin.escorts().create(viewer, reward, "");
                redraw();
            });
        }
    }
}
