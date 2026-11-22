package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;

import java.util.List;

final class MainMenu extends Menu {

    private final CraftovPlugin plugin;

    MainMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 4, Text.mm("战术终端"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        Profile p = plugin.profiles().get(viewer);
        long value = plugin.carry().carriedValue(viewer);
        set(4, UiItems.button("craftov:ui/icon_profile", "<text><b>" + Text.escape(viewer.getName()),
                List.of("<muted>块币 <accent>◈ " + Text.money(p.money),
                        "<muted>等级 <white>Lv." + p.level + " <muted>(" + p.xp + "/" + plugin.ranks().xpForLevel(p.level) + ")",
                        "<muted>排位 " + plugin.ranks().tierName(p.rankPoints) + " <muted>" + p.rankPoints + " 分",
                        "<muted>当前战备值 <white>◈ " + Text.money(value),
                        "<muted>撤离 <white>" + p.stat("extracts") + " <muted>/ 出击 <white>" + p.stat("raids")
                                + " <muted>· 击败干员 <white>" + p.stat("kills"))));
        button(10, UiItems.button("craftov:ui/icon_deploy", "<accent><b>开始行动", List.of("<muted>选择地图、难度、单排或三排", "<muted>支持排位赛与自动补位")),
                e -> plugin.menus().openDeploy(viewer));
        button(11, UiItems.button("craftov:ui/icon_stash", "<info><b>仓库", List.of("<muted>存取物资、整理配装", "<muted>已用 " + p.stashUsed() + "/" + p.stashCapacity)),
                e -> plugin.menus().openStash(viewer));
        button(12, UiItems.button("craftov:ui/icon_trader", "<ok><b>商人", List.of("<muted>购买武器、弹药、护甲、医疗品", "<muted>维修护甲 · 升级安全箱")),
                e -> plugin.menus().openTrader(viewer));
        button(13, UiItems.button("craftov:ui/icon_market", "<accent><b>交易行", List.of("<muted>寄售物资给其他玩家", "<muted>定价合理时系统买家也会收购")),
                e -> plugin.menus().openMarket(viewer));
        button(14, UiItems.button("craftov:ui/icon_operator", "<accent><b>干员", List.of("<muted>当前：" + plugin.operators().of(viewer).name(), "<muted>潜行+F 释放技能")),
                e -> new OperatorMenu(plugin, viewer).open());
        button(15, UiItems.button("craftov:ui/icon_secure", "<info><b>安全箱", List.of("<muted>放入安全箱的物品阵亡也不会丢失",
                "<muted>容量 " + plugin.traders().secureSlots(p) + " 格")), e -> plugin.menus().openSecure(viewer));
        button(16, UiItems.button("craftov:ui/icon_escort", "<ok><b>护航大厅", List.of("<muted>发布或接取护航委托")),
                e -> new EscortMenu(plugin, viewer).open());
        button(20, UiItems.button("craftov:ui/icon_collection", "<danger><b>收藏室", List.of("<muted>完成度 " + plugin.collection().percent(p) + "%")),
                e -> new CollectionMenu(plugin, viewer).open());
        button(21, UiItems.button("craftov:ui/icon_rank", "<accent><b>排位 · 战绩", List.of("<muted>排行榜与最近行动报告")),
                e -> new RankMenu(plugin, viewer).open());
        button(19, UiItems.button("craftov:ui/icon_preset", "<info><b>配装方案", List.of("<muted>保存当前配装，下次一键补齐")),
                e -> new PresetMenu(plugin, viewer).open());
        button(25, UiItems.button("craftov:ui/icon_freekit", "<ok><b>免费补给", List.of("<muted>战备值低于 ◈" + Text.money(plugin.getConfig().getLong("free-kit.max-value", 15000))
                , "<muted>且总资产不超过 ◈" + Text.money(plugin.traders().freeKitMaxAssets()) + " 时可领取",
                "<muted>冷却 " + plugin.getConfig().getInt("free-kit.cooldown-minutes", 20) + " 分钟")), e -> {
            plugin.traders().claimFreeKit(viewer);
            redraw();
        });
        button(22, UiItems.button("craftov:ui/icon_party", "<muted><b>小队", List.of("<muted>/cv party invite <玩家>", "<muted>/cv party leave")),
                e -> {
                    viewer.closeInventory();
                    Text.send(viewer, "<muted>邀请队友：<white>/cv party invite <玩家名></white>，离队：<white>/cv party leave");
                });
        button(23, UiItems.button("craftov:ui/icon_repair", "<text><b>一键维修", List.of("<muted>维修身上所有护甲")),
                e -> {
                    plugin.traders().repairAll(viewer);
                    redraw();
                });
        long tasksDone = plugin.tasks().of(p).stream().filter(t -> t.done).count();
        button(28, UiItems.button("craftov:ui/icon_hideout", "<ok><b>藏身处", List.of("<muted>发电机 · 工作台 · 医疗站 · 情报中心",
                "<muted>制作弹药药品，挂机产出块币")), e -> new HideoutMenu(plugin, viewer).open());
        button(33, UiItems.button("craftov:ui/icon_season", "<accent><b>赛季通行证", List.of("<muted>行动经验解锁 40 级奖励",
                "<muted>块币 · 药品 · 配件 · 每 10 级一把枪")), e -> new SeasonMenu(plugin, viewer).open());
        button(32, UiItems.button("craftov:ui/icon_bounty", "<danger><b>悬赏榜", List.of("<muted>给其他玩家的人头挂上赏金",
                "<muted>在行动中击杀被悬赏者领取全部赏金")), e -> new BountyMenu(plugin, viewer).open());
        button(29, UiItems.button("craftov:ui/icon_tasks", "<accent><b>每日任务", List.of("<muted>今日进度 " + tasksDone + "/"
                + net.infnetwork.snowball.craftov.task.TaskService.COUNT, "<muted>完成即得块币与经验，每天 04:00 刷新")),
                e -> new TaskMenu(plugin, viewer, false).open());
        button(30, UiItems.button("craftov:ui/icon_gunsmith", "<accent><b>改枪台", List.of("<muted>给身上的枪安装 / 拆下瞄准镜")),
                e -> plugin.menus().openGunsmith(viewer));
        if (plugin.canUseCatalog(viewer)) {
            button(31, UiItems.button("craftov:ui/icon_catalog", "<accent><b>测试军械库", List.of("<muted>免费领取任意物品（测试模式）",
                    "<muted>也可以输入 <white>/cv items")), e -> plugin.menus().openCatalog(viewer));
        }
        button(24, UiItems.button("craftov:ui/icon_help", "<text><b>操作说明", List.of(
                "<white>左键 <muted>开火（按住连发）",
                "<white>右键 <muted>按住开镜瞄准，松开即腰射",
                "<white>Q <muted>换弹  <white>潜行+Q <muted>切换弹种",
                "<white>F <muted>切换射击模式  <white>潜行+F <muted>干员技能",
                "<white>右键物资箱 <muted>搜索  <white>对准倒地队友按住右键 <muted>救援",
                "<white>医疗品 <muted>拿在手上按住右键使用，终端里可查看伤势",
                "<muted>物品栏第 2 行是胸挂格，第 3、4 行是背包格")), e -> {
        });
    }
}
