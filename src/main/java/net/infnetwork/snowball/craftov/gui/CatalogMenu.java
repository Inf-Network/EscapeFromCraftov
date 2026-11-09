package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.HealthService;
import net.infnetwork.snowball.craftov.combat.Injury;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Test-mode item catalog: take any item for free, top up money, simulate or clear injuries. */
final class CatalogMenu extends Menu {

    private record Tab(String name, String icon, List<Category> categories) {
    }

    private static final List<Tab> TABS = List.of(
            new Tab("武器", "craftov:ui/tab_weapon", List.of(Category.WEAPON)),
            new Tab("弹药", "craftov:ui/tab_ammo", List.of(Category.AMMO)),
            new Tab("头盔 · 护甲", "craftov:ui/tab_armor", List.of(Category.HELMET, Category.ARMOR)),
            new Tab("胸挂 · 背包", "craftov:ui/tab_carry", List.of(Category.RIG, Category.BACKPACK)),
            new Tab("医疗", "craftov:ui/tab_medical", List.of(Category.MEDICAL)),
            new Tab("投掷物", "craftov:ui/tab_throwable", List.of(Category.THROWABLE)),
            new Tab("钥匙 · 配件", "craftov:ui/tab_attachment", List.of(Category.ATTACHMENT, Category.KEY, Category.SPECIAL)),
            new Tab("物资", "craftov:ui/icon_value", List.of(Category.VALUABLE)),
            new Tab("收藏品", "craftov:ui/icon_collection", List.of(Category.COLLECTIBLE)));

    private final CraftovPlugin plugin;
    private int tab;

    CatalogMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<#F2E6C8>测试军械库"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < TABS.size(); i++) {
            Tab t = TABS.get(i);
            int index = i;
            boolean active = i == tab;
            button(i, UiItems.button(t.icon(), (active ? "<#F2C94C><b>▶ " : "<#C8CCD2>") + t.name(),
                    List.of(active ? "<#5BD16B>当前分类" : "<#9AA0A6>点击切换")), e -> {
                tab = index;
                redraw();
            });
        }
        List<ItemDef> defs = new ArrayList<>();
        for (Category category : TABS.get(tab).categories()) {
            defs.addAll(plugin.registry().byCategory(category));
        }
        defs.sort(Comparator.comparing(ItemDef::category).thenComparing(ItemDef::rarity).thenComparingLong(ItemDef::value));
        for (int i = 0; i < defs.size() && i < 36; i++) {
            ItemDef def = defs.get(i);
            button(9 + i, plugin.items().sample(def), e -> {
                int copies = e.isRightClick() && !def.isGun() ? 5 : 1;
                for (int k = 0; k < copies; k++) {
                    plugin.carry().give(viewer, plugin.items().sample(def), true);
                }
                viewer.playSound(viewer.getLocation(), "minecraft:entity.item.pickup", 0.6f, 1.2f);
                viewer.sendActionBar(Text.mm("<#5BD16B>已领取 " + def.coloredName() + " <#9AA0A6>×" + copies));
            });
        }
        button(45, UiItems.button("craftov:ui/icon_value", "<#F2C94C><b>+ ◈1,000,000", List.of("<#9AA0A6>测试用块币")), e -> {
            Profile p = plugin.profiles().get(viewer);
            p.money += 1_000_000;
            plugin.profiles().markDirty(viewer.getUniqueId());
            viewer.sendActionBar(Text.mm("<#5BD16B>余额 ◈" + Text.money(p.money)));
        });
        button(46, UiItems.button("craftov:ui/icon_repair", "<#5BD16B><b>满血并清除伤病", List.of("<#9AA0A6>生命回满，清除出血和骨折")), e -> {
            plugin.health().clear(viewer);
            plugin.health().heal(viewer, HealthService.MAX_HP);
            plugin.health().forceSpeedUpdate(viewer);
            viewer.sendActionBar(Text.mm("<#5BD16B>状态已恢复"));
        });
        button(47, UiItems.button("craftov:ui/icon_cancel", "<#FF8A80><b>模拟受伤", List.of("<#9AA0A6>生命降到 40：胸部出血、左腿和右臂骨折",
                "<#9AA0A6>用来测试绷带、夹板和急救包")), e -> {
            viewer.setHealth(Math.min(viewer.getHealth(), 40));
            plugin.health().addInjury(viewer, net.infnetwork.snowball.craftov.combat.BodyPart.CHEST, Injury.LIGHT_BLEED);
            plugin.health().addInjury(viewer, net.infnetwork.snowball.craftov.combat.BodyPart.LEFT_LEG, Injury.FRACTURE);
            plugin.health().addInjury(viewer, net.infnetwork.snowball.craftov.combat.BodyPart.RIGHT_ARM, Injury.FRACTURE);
            plugin.health().forceSpeedUpdate(viewer);
        });
        set(49, UiItems.button("craftov:ui/icon_help", "<#C8CCD2><b>说明", List.of(
                "<white>左键 <#9AA0A6>领取 1 个（枪械满弹匣，弹药一整组）",
                "<white>右键 <#9AA0A6>领取 5 个",
                "<#9AA0A6>背包放不下的物品会掉在脚下",
                "<#9AA0A6>本分类 " + defs.size() + " 件")));
        button(53, UiItems.button("craftov:ui/icon_cancel_small", "<#9AA0A6>关闭", List.of()), e -> viewer.closeInventory());
    }
}
