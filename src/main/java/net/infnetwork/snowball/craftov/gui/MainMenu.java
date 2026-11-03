package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;

import java.util.List;

final class MainMenu extends Menu {

    private final CraftovPlugin plugin;

    MainMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 4, Text.mm("<#F2E6C8>战术终端"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        Profile p = plugin.profiles().get(viewer);
        long value = plugin.carry().carriedValue(viewer);
        set(4, UiItems.button("craftov:ui/icon_profile", "<#F2E6C8><b>" + Text.escape(viewer.getName()),
                List.of("<#8A8F98>块币 <#F2C94C>◈ " + Text.money(p.money),
                        "<#8A8F98>等级 <white>Lv." + p.level + " <#8A8F98>(" + p.xp + "/" + plugin.ranks().xpForLevel(p.level) + ")",
                        "<#8A8F98>排位 " + plugin.ranks().tierName(p.rankPoints) + " <#8A8F98>" + p.rankPoints + " 分",
                        "<#8A8F98>当前战备值 <white>◈ " + Text.money(value),
                        "<#8A8F98>撤离 <white>" + p.stat("extracts") + " <#8A8F98>/ 出击 <white>" + p.stat("raids")
                                + " <#8A8F98>· 击败干员 <white>" + p.stat("kills"))));
        button(10, UiItems.button("craftov:ui/icon_deploy", "<#F2C94C><b>开始行动", List.of("<#9AA0A6>选择地图、难度、单排或三排", "<#9AA0A6>支持排位赛与自动补位")),
                e -> plugin.menus().openDeploy(viewer));
        button(11, UiItems.button("craftov:ui/icon_stash", "<#4FA3FF><b>仓库", List.of("<#9AA0A6>存取物资、整理配装", "<#9AA0A6>已用 " + p.stashUsed() + "/" + p.stashCapacity)),
                e -> plugin.menus().openStash(viewer));
        button(12, UiItems.button("craftov:ui/icon_trader", "<#5BD16B><b>商人", List.of("<#9AA0A6>购买武器、弹药、护甲、医疗品", "<#9AA0A6>维修护甲 · 升级安全箱")),
                e -> plugin.menus().openTrader(viewer));
        button(13, UiItems.button("craftov:ui/icon_market", "<#FFC23D><b>交易行", List.of("<#9AA0A6>寄售物资给其他玩家", "<#9AA0A6>定价合理时系统买家也会收购")),
                e -> plugin.menus().openMarket(viewer));
        button(14, UiItems.button("craftov:ui/icon_operator", "<#FF9F43><b>干员", List.of("<#9AA0A6>当前：" + plugin.operators().of(viewer).name(), "<#9AA0A6>潜行+F 释放技能")),
                e -> new OperatorMenu(plugin, viewer).open());
        button(15, UiItems.button("craftov:ui/icon_secure", "<#B66BFF><b>安全箱", List.of("<#9AA0A6>放入安全箱的物品阵亡也不会丢失",
                "<#9AA0A6>容量 " + plugin.traders().secureSlots(p) + " 格")), e -> plugin.menus().openSecure(viewer));
        button(16, UiItems.button("craftov:ui/icon_escort", "<#7FE0D0><b>护航大厅", List.of("<#9AA0A6>发布或接取护航委托")),
                e -> new EscortMenu(plugin, viewer).open());
        button(20, UiItems.button("craftov:ui/icon_collection", "<#FF4D4D><b>收藏室", List.of("<#9AA0A6>完成度 " + plugin.collection().percent(p) + "%")),
                e -> new CollectionMenu(plugin, viewer).open());
        button(21, UiItems.button("craftov:ui/icon_rank", "<#FFC23D><b>排位 · 战绩", List.of("<#9AA0A6>排行榜与最近行动报告")),
                e -> new RankMenu(plugin, viewer).open());
        button(19, UiItems.button("craftov:ui/icon_stash", "<#4FA3FF><b>配装方案", List.of("<#9AA0A6>保存当前配装，下次一键补齐")),
                e -> new PresetMenu(plugin, viewer).open());
        button(25, UiItems.button("craftov:ui/icon_upgrade", "<#5BD16B><b>免费补给", List.of("<#9AA0A6>战备值低于 ◈" + Text.money(plugin.getConfig().getLong("free-kit.max-value", 15000))
                + " 时可领取", "<#9AA0A6>冷却 " + plugin.getConfig().getInt("free-kit.cooldown-minutes", 20) + " 分钟")), e -> {
            plugin.traders().claimFreeKit(viewer);
            redraw();
        });
        button(22, UiItems.button("craftov:ui/icon_party", "<#9AA0A6><b>小队", List.of("<#9AA0A6>/cv party invite <玩家>", "<#9AA0A6>/cv party leave")),
                e -> {
                    viewer.closeInventory();
                    Text.send(viewer, "<#9AA0A6>邀请队友：<white>/cv party invite <玩家名></white>，离队：<white>/cv party leave");
                });
        button(23, UiItems.button("craftov:ui/icon_repair", "<#C8CCD2><b>一键维修", List.of("<#9AA0A6>维修身上所有护甲")),
                e -> {
                    plugin.traders().repairAll(viewer);
                    redraw();
                });
        button(24, UiItems.button("craftov:ui/icon_help", "<#C8CCD2><b>操作说明", List.of(
                "<white>右键 <#9AA0A6>开火（按住连发）",
                "<white>左键 <#9AA0A6>开镜 / 关镜",
                "<white>Q <#9AA0A6>换弹  <white>潜行+Q <#9AA0A6>切换弹种",
                "<white>F <#9AA0A6>切换射击模式  <white>潜行+F <#9AA0A6>干员技能",
                "<white>右键物资箱 <#9AA0A6>搜索  <white>潜行靠近倒地队友 <#9AA0A6>救援",
                "<#9AA0A6>物品栏第 2 行是胸挂格，第 3、4 行是背包格")), e -> {
        });
    }
}
