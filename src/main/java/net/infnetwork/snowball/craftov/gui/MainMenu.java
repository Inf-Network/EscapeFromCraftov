package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The lobby tactical terminal, on the "main" screen (tools/art/screens.py): four labelled sections.
 * Operations is the big deploy button (six slots, one action) with daily tasks and the range;
 * preparation holds the stash, trader, market, secure container, presets, gunsmith, repair and free
 * kit; progress the operator, hideout, collection, season pass and rankings; social the squad,
 * escort hall, bounty board and appearance (knife skins). The bottom bar keeps help, the profile and
 * close in fixed places.
 */
final class MainMenu extends Menu {

    /** The deploy button's cells: two columns by three rows. */
    static final int[] DEPLOY = {9, 10, 18, 19, 27, 28};

    private final CraftovPlugin plugin;

    MainMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 6, "main", Text.mm("战术终端"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        Profile p = plugin.profiles().get(viewer);
        long value = plugin.carry().gearValue(viewer);
        // Operations.
        bigButton(DEPLOY, "<accent><b>开始行动", List.of("<muted>选择地图、难度、单排或三排", "<muted>支持排位赛与自动补位"),
                e -> plugin.menus().openDeploy(viewer));
        if (plugin.canUseCatalog(viewer)) {
            button(36, UiItems.button("craftov:ui/icon_catalog", "<text><b>测试军械库", List.of("<muted>免费领取任意物品（测试模式）",
                    "<muted>也可以输入 <white>/cv items")), e -> plugin.menus().openCatalog(viewer));
        }
        button(37, UiItems.button("craftov:ui/tab_weapon", "<text><b>靶场", List.of("<muted>借用任意武器试射，离开时归还",
                "<muted>身上的装备会先替你保管")), e -> plugin.menus().openStation(viewer, "range_rack"));
        // Preparation.
        button(11, UiItems.button("craftov:ui/icon_stash", "<text><b>仓库", List.of("<muted>存取物资、整理配装", "<muted>已用 " + p.stashUsed() + "/" + p.stashCapacity)),
                e -> plugin.menus().openStash(viewer));
        button(12, UiItems.button("craftov:ui/icon_trader", "<text><b>商人", List.of("<muted>购买武器、弹药、护甲、医疗品", "<muted>维修护甲 · 升级安全箱")),
                e -> plugin.menus().openTrader(viewer));
        button(13, UiItems.button("craftov:ui/icon_market", "<text><b>交易行", List.of("<muted>寄售物资给其他玩家", "<muted>定价合理时系统买家也会收购")),
                e -> plugin.menus().openMarket(viewer));
        button(20, UiItems.button("craftov:ui/icon_secure", "<text><b>安全箱", List.of("<muted>放入安全箱的物品阵亡也不会丢失",
                "<muted>容量 " + plugin.traders().secureSlots(p) + " 格")), e -> plugin.menus().openSecure(viewer));
        button(21, UiItems.button("craftov:ui/icon_preset", "<text><b>配装方案", List.of("<muted>保存当前配装，下次一键补齐")),
                e -> new PresetMenu(plugin, viewer).open());
        button(22, UiItems.button("craftov:ui/icon_gunsmith", "<text><b>改枪台", List.of("<muted>给身上的枪安装 / 拆下瞄准镜和配件")),
                e -> plugin.menus().openGunsmith(viewer));
        button(29, UiItems.button("craftov:ui/icon_repair", "<text><b>一键维修", List.of("<muted>维修身上所有护甲")),
                e -> {
                    plugin.traders().repairAll(viewer);
                    redraw();
                });
        button(30, UiItems.button("craftov:ui/icon_freekit", "<text><b>免费补给", List.of("<muted>战备值低于 ◈" + Text.money(plugin.getConfig().getLong("free-kit.max-value", 15000))
                , "<muted>且总资产不超过 ◈" + Text.money(plugin.traders().freeKitMaxAssets()) + " 时可领取",
                "<dim>总资产：块币、仓库、邮箱、理赔途中的装备、",
                "<dim>携带、安全箱、交易行上架、托管中的护航赏金",
                "<muted>冷却 " + plugin.getConfig().getInt("free-kit.cooldown-minutes", 20) + " 分钟")), e -> {
            plugin.traders().claimFreeKit(viewer);
            redraw();
        });
        // Progress.
        button(14, UiItems.button("craftov:ui/icon_operator", "<text><b>干员", List.of("<muted>当前：" + plugin.operators().of(viewer).name(), "<muted>潜行+F 释放技能")),
                e -> new OperatorMenu(plugin, viewer).open());
        button(15, UiItems.button("craftov:ui/icon_hideout", "<text><b>藏身处", List.of("<muted>发电机 · 工作台 · 医疗站 · 情报中心",
                "<muted>制作弹药药品，挂机产出块币")), e -> new HideoutMenu(plugin, viewer).open());
        button(23, UiItems.button("craftov:ui/icon_collection", "<text><b>收藏室", List.of("<muted>完成度 " + plugin.collection().percent(p) + "%")),
                e -> new CollectionMenu(plugin, viewer).open());
        button(24, UiItems.button("craftov:ui/icon_season", "<text><b>赛季通行证", List.of("<muted>行动经验解锁 40 级奖励",
                "<muted>块币 · 药品 · 配件 · 每 10 级一把枪")), e -> new SeasonMenu(plugin, viewer).open());
        button(32, UiItems.button("craftov:ui/icon_rank", "<text><b>排位 · 战绩", List.of("<muted>排行榜与最近行动报告")),
                e -> new RankMenu(plugin, viewer).open());
        long tasksDone = plugin.tasks().of(p).stream().filter(t -> t.done).count();
        button(33, UiItems.button("craftov:ui/icon_tasks", "<text><b>每日任务", List.of("<muted>今日进度 " + tasksDone + "/"
                + net.infnetwork.snowball.craftov.task.TaskService.COUNT, "<muted>完成即得块币与经验，每天 04:00 刷新")),
                e -> new TaskMenu(plugin, viewer, false).open());
        // Social. The escort hall stays on slot 16, where the bots look for it.
        button(16, UiItems.button("craftov:ui/icon_escort", "<text><b>护航大厅", List.of("<muted>发布或接取护航委托")),
                e -> new EscortMenu(plugin, viewer).open());
        button(17, UiItems.button("craftov:ui/icon_party", "<text><b>小队", List.of("<muted>/cv party invite <玩家>", "<muted>/cv party leave")),
                e -> {
                    viewer.closeInventory();
                    Text.send(viewer, "<muted>邀请队友：<white>/cv party invite <玩家名></white>，离队：<white>/cv party leave");
                });
        button(25, UiItems.button("craftov:ui/icon_bounty", "<text><b>悬赏榜", List.of("<muted>给其他玩家的人头挂上赏金",
                "<muted>在行动中击杀被悬赏者领取全部赏金")), e -> new BountyMenu(plugin, viewer).open());
        button(26, UiItems.button("craftov:ui/icon_skins", "<text><b>外观", List.of("<muted>刀具皮肤：只改变外观，不是物品",
                "<muted>预览检视动作 · 点券 <info>" + pointsLine())), e -> plugin.menus().openSkins(viewer, null));
        // Bottom bar: help, profile, close.
        set(45, UiItems.button("craftov:ui/icon_help", "<text><b>操作说明", List.of(
                "<white>左键 <muted>开火（按住连发）",
                "<white>右键 <muted>按住开镜瞄准，松开即腰射",
                "<white>Q <muted>换弹  <white>潜行+Q <muted>切换弹种",
                "<white>F <muted>切换射击模式（拿刀时检视）  <white>潜行+F <muted>干员技能",
                "<white>右键物资箱 <muted>搜索  <white>对准倒地队友按住右键 <muted>救援",
                "<white>医疗品 <muted>拿在手上按住右键使用，终端里可查看伤势",
                "<muted>物品栏第 2 行是胸挂格，第 3、4 行是背包格")));
        set(49, UiItems.button("craftov:ui/icon_profile", "<text><b>" + Text.escape(viewer.getName()),
                List.of("<muted>块币 <accent>◈ " + Text.money(p.money),
                        "<muted>点券 <info>" + pointsLine(),
                        "<muted>等级 <white>Lv." + p.level + " <muted>(" + p.xp + "/" + plugin.ranks().xpForLevel(p.level) + ")",
                        "<muted>排位 " + plugin.ranks().tierName(p.rankPoints) + " <muted>" + p.rankPoints + " 分",
                        "<muted>当前战备值 <white>◈ " + Text.money(value),
                        "<muted>撤离 <white>" + p.stat("extracts") + " <muted>/ 出击 <white>" + p.stat("raids")
                                + " <muted>· 击败干员 <white>" + p.stat("kills"))));
        closeButton(53);
    }

    /** The viewer's 点券 balance (the server's premium currency, #106), or a dash when it cannot be read. */
    private String pointsLine() {
        long points = plugin.points().balance(viewer.getUniqueId());
        return points < 0 ? "—" : Text.money(points);
    }
}
