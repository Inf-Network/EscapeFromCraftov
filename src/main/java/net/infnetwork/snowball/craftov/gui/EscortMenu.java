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
        super(viewer, 5, Text.mm("<text>护航大厅"));
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
            List<String> lore = List.of("<muted>委托人 <white>" + Text.escape(c.clientName),
                    "<muted>赏金 <accent>◈" + Text.money(c.reward),
                    "<muted>护航 " + c.escorts.size() + "/2 <white>" + Text.escape(escorts.toString().trim()),
                    "<muted>状态 <white>" + switch (c.state) {
                        case OPEN -> "等待护航";
                        case ACCEPTED -> "已接取";
                        case IN_RAID -> "行动中";
                    },
                    c.client.equals(viewer.getUniqueId()) ? "<danger>点击取消委托（退还赏金）" : "<ok>点击接取，加入委托人小队");
            button(slot++, UiItems.button("craftov:ui/icon_escort", "<ok><b>护航委托 #" + c.id, lore), e -> {
                if (c.client.equals(viewer.getUniqueId())) {
                    plugin.escorts().cancel(viewer);
                } else {
                    plugin.escorts().accept(viewer, c.id);
                }
                redraw();
            });
        }
        if (slot == 0) {
            set(13, UiItems.button("craftov:ui/icon_escort", "<muted>暂时没有护航委托", List.of("<muted>发布委托，让高手护送你撤离")));
        }
        fillRow(3, UiItems.filler());
        set(36, UiItems.button("craftov:ui/icon_help", "<white>护航规则", List.of(
                "<muted>委托人托管赏金，护航者加入其小队一同出击。",
                "<muted>委托人成功撤离：护航者平分全部赏金。",
                "<muted>委托人阵亡：护航者获得 30%，其余退回。",
                "<muted>每人同时只能参与一份委托，委托人取消自己的才能接别的。",
                "<muted>出发前离开委托人小队即放弃；委托人出发后要等这局结束。",
                "<muted>只有和委托人同队出发的护航者参与分账。")));
        int s = 38;
        for (long reward : REWARDS) {
            button(s++, UiItems.button("craftov:ui/icon_value", "<accent>发布委托 ◈" + Text.money(reward), List.of("<muted>赏金立即托管")), e -> {
                plugin.escorts().create(viewer, reward, "");
                redraw();
            });
        }
    }
}
