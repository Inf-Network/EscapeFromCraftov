package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Comparator;
import java.util.List;

/**
 * Shooting-range weapon rack: free loaner guns (loaded, unlimited reserve on the range) and optics
 * to try on them. Loaners are marked and taken back when the player leaves the firing line.
 */
final class RangeMenu extends Menu {

    private final CraftovPlugin plugin;

    RangeMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 5, Text.mm("<#F2E6C8>靶场武器架"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        List<ItemDef> guns = plugin.registry().byCategory(Category.WEAPON);
        guns.sort(Comparator.comparing(ItemDef::rarity).thenComparingLong(ItemDef::value));
        for (int i = 0; i < guns.size() && i < 18; i++) {
            ItemDef def = guns.get(i);
            button(i, plugin.items().markRange(plugin.items().sample(def)), e -> take(def));
        }
        List<ItemDef> optics = plugin.registry().byCategory(Category.ATTACHMENT);
        optics.sort(Comparator.comparingLong(ItemDef::value));
        for (int i = 0; i < optics.size() && i < 9; i++) {
            ItemDef def = optics.get(i);
            button(27 + i, plugin.items().markRange(plugin.items().create(def, 1)), e -> take(def));
        }
        button(38, UiItems.button("craftov:ui/tab_attachment", "<#FF9F43><b>改枪台", List.of("<#9AA0A6>给靶场武器装上 / 拆下瞄准镜")),
                e -> plugin.menus().openGunsmith(viewer));
        button(40, UiItems.button("craftov:ui/icon_help", "<#C8CCD2><b>说明", List.of(
                "<#9AA0A6>点击领取，子弹无限（弹匣/999）",
                "<#9AA0A6>靶场武器不能存进仓库、不能出售",
                "<#9AA0A6>离开靶场时全部清空，原装备原样归还")), e -> {
        });
        button(42, UiItems.button("craftov:ui/icon_cancel", "<#FF8A80><b>归还全部靶场武器", List.of()), e -> {
            int n = plugin.lobby().stripRangeItems(viewer);
            viewer.sendActionBar(Text.mm("<#9AA0A6>已归还 " + n + " 件靶场武器"));
        });
    }

    private void take(ItemDef def) {
        if (!plugin.lobby().inRange(viewer)) {
            viewer.sendActionBar(Text.mm("<#FF8A80>请先从大厅的靶场入口进入个人靶场"));
            return;
        }
        ItemStack stack = def.isGun() ? plugin.items().sample(def) : plugin.items().create(def, 1);
        ItemStack left = plugin.carry().give(viewer, plugin.items().markRange(stack), false);
        if (left != null) {
            viewer.sendActionBar(Text.mm("<#FF8A80>身上放不下了"));
            return;
        }
        viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", 0.7f, 1f);
        viewer.sendActionBar(Text.mm("<#5BD16B>领取 " + def.coloredName() + " <#9AA0A6>· 靶场内子弹无限"));
    }
}
