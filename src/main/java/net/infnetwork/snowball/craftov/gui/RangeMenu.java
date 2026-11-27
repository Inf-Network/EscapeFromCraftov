package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.operator.OperatorDef;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Comparator;
import java.util.List;

/**
 * Shooting-range weapon rack: free loaner guns (loaded, unlimited reserve on the range) and optics
 * to try on them, carried in the loaner rig and backpack the range puts on (RangeService). Loaners
 * are marked and taken back when the player leaves the range. Its bottom row also tries out
 * operators and their skills (OperatorService) and clears the bullseyes (RangeTargets).
 */
final class RangeMenu extends Menu {

    private final CraftovPlugin plugin;

    RangeMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 5, Text.mm("<text>靶场武器架"));
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
        for (int i = 0; i < optics.size() && i < 18; i++) {
            ItemDef def = optics.get(i);
            button(18 + i, plugin.items().markRange(plugin.items().create(def, 1)), e -> take(def));
        }
        button(38, UiItems.button("craftov:ui/icon_gunsmith", "<accent><b>改枪台", List.of("<muted>给靶场武器装上 / 拆下瞄准镜和配件")),
                e -> plugin.menus().openGunsmith(viewer));
        button(40, UiItems.button("craftov:ui/icon_help", "<text><b>说明", List.of(
                "<muted>点击领取，子弹无限（弹匣/999）",
                "<muted>身上的靶场胸挂和背包装得下这里的全部配件",
                "<muted>靶场物品不能存进仓库、不能出售",
                "<muted>离开靶场时全部清空，原装备原样归还")), e -> {
        });
        button(42, UiItems.button("craftov:ui/icon_cancel", "<danger><b>归还全部靶场武器", List.of("<muted>胸挂和背包留在身上")), e -> {
            int n = plugin.lobby().stripRangeItems(viewer, true);
            Notify.ok(viewer, "已归还 " + n + " 件靶场武器");
        });
        OperatorDef op = plugin.operators().of(viewer);
        OperatorDef own = plugin.operators().chosen(viewer);
        button(36, UiItems.button("craftov:ui/icon_operator", "<text><b>试用干员", List.of(
                "<muted>当前 <" + op.color() + ">" + op.name() + " <muted>(" + op.role() + ")",
                "<muted>只在靶场有效，离开后仍是 <white>" + own.name())), e -> new OperatorMenu(plugin, viewer).open());
        int cd = plugin.operators().cooldownLeft(viewer);
        button(37, UiItems.button(op.icon(), "<" + op.color() + "><b>" + op.skillName(), List.of(
                cd > 0 ? "<danger>冷却 " + cd + "s" : "<ok>点击释放",
                "<muted>也可以使用 潜行+F",
                "<muted>靶场里冷却更短，离开后清零，不影响行动")), e -> {
            viewer.closeInventory();
            plugin.operators().activate(viewer);
        });
        button(44, UiItems.button("craftov:ui/icon_target", "<accent><b>清空标靶记录", List.of(
                "<muted>两块标靶的命中数、命中率、环数和弹孔")), e -> {
            plugin.range().targets().resetTally(viewer);
            Notify.ok(viewer, "标靶记录已清空");
        });
    }

    private void take(ItemDef def) {
        if (!plugin.lobby().inRange(viewer)) {
            Notify.error(viewer, "请先从大厅的靶场入口进入个人靶场");
            return;
        }
        ItemStack stack = plugin.items().markRange(def.isGun() ? plugin.items().sample(def) : plugin.items().create(def, 1));
        // Attachments fill the rig before the backpack, which is the only room guns have besides the
        // two gun keys: everything on the rack fits at once whatever order it is taken in.
        ItemStack left = def.isGun() ? plugin.carry().give(viewer, stack, false) : plugin.carry().giveRigFirst(viewer, stack);
        if (left != null) {
            Notify.error(viewer, "身上放不下了");
            return;
        }
        viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", 0.7f, 1f);
        Notify.ok(viewer, "领取 " + def.coloredName() + " <muted>· 靶场内子弹无限");
    }
}
