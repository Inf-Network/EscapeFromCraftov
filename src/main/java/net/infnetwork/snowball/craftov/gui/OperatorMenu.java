package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.operator.OperatorDef;
import net.infnetwork.snowball.craftov.operator.OperatorService;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the operator. On the private range it picks one to try there only (OperatorService): the
 * title and the cards say so, and a button that leads back to the weapon rack names the operator the
 * player has again on leaving.
 */
final class OperatorMenu extends Menu {

    private final CraftovPlugin plugin;
    private final boolean range;

    OperatorMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 3, Text.mm(plugin.range().isIn(viewer) ? "<text>干员 · 靶场试用" : "<text>干员"));
        this.plugin = plugin;
        this.range = plugin.range().isIn(viewer);
    }

    @Override
    protected void draw() {
        String current = plugin.operators().of(viewer).id();
        String own = plugin.operators().chosen(viewer).name();
        if (range) {
            button(22, UiItems.button("craftov:ui/arrow_left", "<text><b>返回武器架", List.of(
                    "<muted>试用的干员只在靶场有效", "<muted>离开靶场后仍是 <white>" + own)), e -> new RangeMenu(plugin, viewer).open());
        }
        int slot = 10;
        for (OperatorDef def : OperatorService.OPERATORS.values()) {
            List<String> lore = new ArrayList<>();
            lore.add("<muted>定位 <white>" + def.role());
            lore.add("<muted>技能 <" + def.color() + ">" + def.skillName() + " <muted>(冷却 " + def.cooldownSeconds() + "s)");
            for (String line : def.description()) {
                lore.add("<muted>" + line);
            }
            lore.add(def.id().equals(current) ? (range ? "<ok>正在试用" : "<ok>当前干员") : (range ? "<muted>点击试用（只在靶场有效）" : "<muted>点击选择"));
            button(slot, UiItems.button(def.icon(), (def.id().equals(current) ? "<accent>▶ " : "") + "<" + def.color() + "><b>" + def.name(), lore), e -> {
                plugin.operators().select(viewer, def.id());
                redraw();
            });
            slot += 2;
        }
    }
}
