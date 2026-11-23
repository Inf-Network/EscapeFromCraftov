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
        super(viewer, 5, Text.mm("<text>排位 · 战绩"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        Profile p = plugin.profiles().get(viewer);
        set(4, UiItems.button("craftov:ui/icon_rank", "<b>" + plugin.ranks().tierName(p.rankPoints) + " <accent>" + p.rankPoints + " 分",
                List.of("<muted>撤离 " + p.stat("extracts") + " · 阵亡 " + p.stat("deaths") + " · 出击 " + p.stat("raids"),
                        "<muted>击败干员 " + p.stat("kills") + " · 击败敌兵 " + p.stat("ai_kills"),
                        "<muted>累计带出 ◈" + Text.money(p.stat("extracted_value")))));
        List<String> top = new ArrayList<>();
        int i = 1;
        for (ProfileService.Summary s : plugin.profiles().leaderboard((a, b) -> Integer.compare(b.rankPoints(), a.rankPoints()), 10)) {
            top.add("<muted>" + i++ + ". <white>" + Text.escape(s.name()) + " " + plugin.ranks().tierName(s.rankPoints()) + " <accent>" + s.rankPoints());
        }
        set(13, UiItems.button("craftov:ui/icon_rank", "<accent><b>排位榜 TOP 10", top.isEmpty() ? List.of("<muted>暂无") : top));
        List<String> rich = new ArrayList<>();
        i = 1;
        for (ProfileService.Summary s : plugin.profiles().leaderboard((a, b) -> Long.compare(b.extracted(), a.extracted()), 10)) {
            rich.add("<muted>" + i++ + ". <white>" + Text.escape(s.name()) + " <accent>◈" + Text.moneyShort(s.extracted()));
        }
        set(15, UiItems.button("craftov:ui/icon_value", "<accent><b>收益榜 TOP 10", rich.isEmpty() ? List.of("<muted>暂无") : rich));
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
        int slot = 27;
        for (Profile.RaidReport r : p.reports) {
            if (slot >= 45) {
                break;
            }
            boolean ok = r.outcome != null && r.outcome.startsWith("撤离成功");
            set(slot++, UiItems.button(ok ? "craftov:ui/report_ok" : "craftov:ui/report_fail",
                    (ok ? "<ok>" : "<danger>") + Text.escape(r.outcome == null ? "" : r.outcome),
                    List.of("<muted>" + fmt.format(new Date(r.time)) + " · " + Text.escape(r.map) + " " + Text.escape(r.tier),
                            "<muted>用时 " + Text.duration(r.seconds) + " · 击败 " + r.kills + "/" + r.aiKills,
                            r.extractedValue > 0 ? "<ok>收益 +◈" + Text.money(r.extractedValue) : "<danger>损失 -◈" + Text.money(r.lostValue),
                            r.rankDelta != 0 ? "<muted>排位分 " + (r.rankDelta > 0 ? "+" : "") + r.rankDelta : "")));
        }
    }
}
