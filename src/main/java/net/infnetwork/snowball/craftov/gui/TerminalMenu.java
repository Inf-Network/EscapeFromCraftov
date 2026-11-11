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
        super(viewer, 3, Text.mm("<#F2E6C8>战术终端"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        Raid raid = plugin.raids().raidOf(viewer);
        RaidPlayer rp = plugin.raids().session(viewer);
        if (raid == null || rp == null) {
            set(13, UiItems.button("craftov:ui/icon_help", "<#9AA0A6>不在行动中", List.of()));
            return;
        }
        String wounds = plugin.hud().data().injuries(viewer);
        button(4, UiItems.button("craftov:ui/body_chest", "<#FF8A80><b>伤势", List.of(
                wounds.isEmpty() ? "<#5BD16B>身体状况良好" : "<#FFB74D>" + Text.escape(wounds),
                "<#9AA0A6>查看每个部位的伤口与治疗方法")), e -> new HealthMenu(plugin, viewer).open());
        button(10, UiItems.button("craftov:ui/icon_secure", "<#B66BFF><b>安全箱", List.of("<#9AA0A6>阵亡也不会丢失的物品")),
                e -> plugin.menus().openSecure(viewer));
        List<String> extracts = new ArrayList<>();
        for (ExtractPoint point : raid.extracts) {
            String state;
            if (!point.available) {
                state = "<#6B7079>本局未开放";
            } else {
                String blocker = plugin.raids().extractBlocker(raid, point, viewer);
                state = blocker == null ? "<#5BD16B>可用" : "<#FFC23D>" + Text.escape(blocker);
            }
            int distance = point.center.getWorld() == viewer.getWorld() ? (int) point.center.distance(viewer.getLocation()) : -1;
            extracts.add("<white>" + Text.escape(point.def.name()) + " <#8A8F98>" + point.def.type().displayName() + " · "
                    + plugin.hud().data().arrow(viewer, point.center) + " " + distance + "m · " + state);
        }
        set(12, UiItems.button("craftov:ui/icon_map", "<#5BD16B><b>撤离点情报", extracts));
        List<String> squad = new ArrayList<>();
        for (RaidPlayer mate : raid.squadOf(rp)) {
            Player p = Bukkit.getPlayer(mate.uuid);
            String hp = p == null ? "" : " <#8A8F98>❤" + (int) Math.ceil(p.getHealth());
            squad.add("<white>" + Text.escape(mate.name) + " <#8A8F98>" + switch (mate.state) {
                case ALIVE -> "<#5BD16B>存活" + hp;
                case DOWNED -> "<#FF4D4D>倒地";
                case DEAD -> "<#6B7079>阵亡";
                case EXTRACTED -> "<#4FA3FF>已撤离";
                case DISCONNECTED -> "<#FFC23D>断线";
                default -> "<#8A8F98>-";
            });
        }
        set(14, UiItems.button("craftov:ui/icon_party", "<#4FA3FF><b>小队状态", squad));
        var op = plugin.operators().of(viewer);
        int cd = plugin.operators().cooldownLeft(viewer);
        button(16, UiItems.button(op.icon(), "<" + op.color() + "><b>" + op.skillName(), List.of(cd > 0 ? "<#FF8A80>冷却 " + cd + "s" : "<#5BD16B>点击释放",
                "<#9AA0A6>也可以使用 潜行+F")), e -> {
            viewer.closeInventory();
            plugin.operators().activate(viewer);
        });
        button(24, UiItems.button("craftov:ui/icon_start", "<#F2C94C><b>每日任务", List.of("<#9AA0A6>查看今日任务进度")),
                e -> new TaskMenu(plugin, viewer, true).open());
        button(20, UiItems.button("craftov:ui/tab_attachment", "<#FF9F43><b>改枪台", List.of("<#9AA0A6>安装 / 拆下瞄准镜")),
                e -> plugin.menus().openGunsmith(viewer));
        if (plugin.canUseCatalog(viewer)) {
            button(26, UiItems.button("craftov:ui/tab_weapon", "<#FF9F43><b>测试军械库", List.of("<#9AA0A6>免费领取任意物品（测试模式）")),
                    e -> plugin.menus().openCatalog(viewer));
        }
        button(22, UiItems.button("craftov:ui/icon_cancel", confirmSurrender ? "<#FF4D4D><b>再次点击确认放弃" : "<#FF8A80>放弃行动",
                List.of("<#9AA0A6>视为阵亡，丢失除安全箱外的全部物品")), e -> {
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
