package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.profile.ProfileService;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

final class RankMenu extends Menu {

    private final CraftovPlugin plugin;

    RankMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 5, Text.mm("<#F2E6C8>排位 · 战绩"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        Profile p = plugin.profiles().get(viewer);
        set(4, UiItems.button("craftov:ui/icon_rank", "<b>" + plugin.ranks().tierName(p.rankPoints) + " <#F2C94C>" + p.rankPoints + " 分",
                List.of("<#8A8F98>撤离 " + p.stat("extracts") + " · 阵亡 " + p.stat("deaths") + " · 出击 " + p.stat("raids"),
                        "<#8A8F98>击败干员 " + p.stat("kills") + " · 击败敌兵 " + p.stat("ai_kills"),
                        "<#8A8F98>累计带出 ◈" + Text.money(p.stat("extracted_value")))));
        List<String> top = new ArrayList<>();
        int i = 1;
        for (ProfileService.Summary s : plugin.profiles().leaderboard((a, b) -> Integer.compare(b.rankPoints(), a.rankPoints()), 10)) {
            top.add("<#8A8F98>" + i++ + ". <white>" + Text.escape(s.name()) + " " + plugin.ranks().tierName(s.rankPoints()) + " <#F2C94C>" + s.rankPoints());
        }
        set(13, UiItems.button("craftov:ui/icon_rank", "<#FFC23D><b>排位榜 TOP 10", top.isEmpty() ? List.of("<#8A8F98>暂无") : top));
        List<String> rich = new ArrayList<>();
        i = 1;
        for (ProfileService.Summary s : plugin.profiles().leaderboard((a, b) -> Long.compare(b.extracted(), a.extracted()), 10)) {
            rich.add("<#8A8F98>" + i++ + ". <white>" + Text.escape(s.name()) + " <#F2C94C>◈" + Text.moneyShort(s.extracted()));
        }
        set(15, UiItems.button("craftov:ui/icon_value", "<#F2C94C><b>收益榜 TOP 10", rich.isEmpty() ? List.of("<#8A8F98>暂无") : rich));
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
        int slot = 27;
        for (Profile.RaidReport r : p.reports) {
            if (slot >= 45) {
                break;
            }
            boolean ok = r.outcome != null && r.outcome.startsWith("撤离成功");
            set(slot++, UiItems.button(ok ? "craftov:ui/report_ok" : "craftov:ui/report_fail",
                    (ok ? "<#5BD16B>" : "<#FF8A80>") + Text.escape(r.outcome == null ? "" : r.outcome),
                    List.of("<#8A8F98>" + fmt.format(new Date(r.time)) + " · " + Text.escape(r.map) + " " + Text.escape(r.tier),
                            "<#8A8F98>用时 " + Text.duration(r.seconds) + " · 击败 " + r.kills + "/" + r.aiKills,
                            r.extractedValue > 0 ? "<#5BD16B>收益 +◈" + Text.money(r.extractedValue) : "<#FF8A80>损失 -◈" + Text.money(r.lostValue),
                            r.rankDelta != 0 ? "<#8A8F98>排位分 " + (r.rankDelta > 0 ? "+" : "") + r.rankDelta : "")));
        }
    }
}
