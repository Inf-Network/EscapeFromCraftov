package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.raid.ExtractPoint;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** In-raid tactical terminal: secure container, extract intel, squad and skill. */
final class TerminalMenu extends Menu {

    private final CraftovPlugin plugin;
    private boolean confirmSurrender;

    TerminalMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 3, Text.mm("<text>战术终端"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        Raid raid = plugin.raids().raidOf(viewer);
        RaidPlayer rp = plugin.raids().session(viewer);
        if (raid == null || rp == null) {
            set(13, UiItems.button("craftov:ui/icon_help", "<muted>不在行动中", List.of()));
            return;
        }
        String wounds = plugin.hud().data().injuries(viewer);
        button(4, UiItems.button("craftov:ui/body_chest", "<danger><b>伤势", List.of(
                wounds.isEmpty() ? "<ok>身体状况良好" : "<accent>" + Text.escape(wounds),
                "<muted>查看每个部位的伤口与治疗方法")), e -> new HealthMenu(plugin, viewer).open());
        button(10, UiItems.button("craftov:ui/icon_secure", "<info><b>安全箱", List.of("<muted>阵亡也不会丢失的物品")),
                e -> plugin.menus().openSecure(viewer));
        List<String> extracts = new ArrayList<>();
        for (ExtractPoint point : raid.extracts) {
            String state;
            if (!point.available) {
                state = "<dim>本局未开放";
            } else {
                String blocker = plugin.raids().extractBlocker(raid, point, viewer);
                state = blocker == null ? "<ok>可用" : "<accent>" + Text.escape(blocker);
            }
            int distance = point.center.getWorld() == viewer.getWorld() ? (int) point.center.distance(viewer.getLocation()) : -1;
            extracts.add("<white>" + Text.escape(point.def.name()) + " <muted>" + point.def.type().displayName() + " · "
                    + plugin.hud().data().arrow(viewer, point.center) + " " + distance + "m · " + state);
        }
        set(12, UiItems.button("craftov:ui/icon_map", "<ok><b>撤离点情报", extracts));
        List<String> squad = new ArrayList<>();
        for (RaidPlayer mate : raid.squadOf(rp)) {
            Player p = Bukkit.getPlayer(mate.uuid);
            String hp = p == null ? "" : " <muted>❤" + (int) Math.ceil(p.getHealth());
            squad.add("<white>" + Text.escape(mate.name) + " <muted>" + switch (mate.state) {
                case ALIVE -> "<ok>存活" + hp;
                case DOWNED -> "<danger>倒地";
                case DEAD -> "<dim>阵亡";
                case EXTRACTED -> "<info>已撤离";
                case DISCONNECTED -> "<accent>断线";
                default -> "<muted>-";
            });
        }
        set(14, UiItems.button("craftov:ui/icon_party", "<info><b>小队状态", squad));
        var op = plugin.operators().of(viewer);
        int cd = plugin.operators().cooldownLeft(viewer);
        button(16, UiItems.button(op.icon(), "<" + op.color() + "><b>" + op.skillName(), List.of(cd > 0 ? "<danger>冷却 " + cd + "s" : "<ok>点击释放",
                "<muted>也可以使用 潜行+F")), e -> {
            viewer.closeInventory();
            plugin.operators().activate(viewer);
        });
        button(24, UiItems.button("craftov:ui/icon_start", "<accent><b>每日任务", List.of("<muted>查看今日任务进度")),
                e -> new TaskMenu(plugin, viewer, true).open());
        button(20, UiItems.button("craftov:ui/tab_attachment", "<accent><b>改枪台", List.of("<muted>安装 / 拆下瞄准镜")),
                e -> plugin.menus().openGunsmith(viewer));
        if (plugin.canUseCatalog(viewer)) {
            button(26, UiItems.button("craftov:ui/tab_weapon", "<accent><b>测试军械库", List.of("<muted>免费领取任意物品（测试模式）")),
                    e -> plugin.menus().openCatalog(viewer));
        }
        button(22, UiItems.button("craftov:ui/icon_cancel", confirmSurrender ? "<danger><b>再次点击确认放弃" : "<danger>放弃行动",
                List.of("<muted>视为阵亡，丢失除安全箱外的全部物品")), e -> {
            if (confirmSurrender) {
                viewer.closeInventory();
                plugin.raids().surrender(viewer);
            } else {
                confirmSurrender = true;
                redraw();
            }
        });
    }
}
