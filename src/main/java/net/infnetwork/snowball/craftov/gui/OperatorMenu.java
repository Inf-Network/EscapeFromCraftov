package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.operator.OperatorDef;
import net.infnetwork.snowball.craftov.operator.OperatorService;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

final class OperatorMenu extends Menu {

    private final CraftovPlugin plugin;

    OperatorMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 3, Text.mm("<text>干员"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        String current = plugin.operators().of(viewer).id();
        int slot = 10;
        for (OperatorDef def : OperatorService.OPERATORS.values()) {
            List<String> lore = new ArrayList<>();
            lore.add("<muted>定位 <white>" + def.role());
            lore.add("<muted>技能 <" + def.color() + ">" + def.skillName() + " <muted>(冷却 " + def.cooldownSeconds() + "s)");
            for (String line : def.description()) {
                lore.add("<muted>" + line);
            }
            lore.add(def.id().equals(current) ? "<ok>当前干员" : "<muted>点击选择");
            button(slot, UiItems.button(def.icon(), (def.id().equals(current) ? "<accent>▶ " : "") + "<" + def.color() + "><b>" + def.name(), lore), e -> {
                plugin.operators().select(viewer, def.id());
                redraw();
            });
            slot += 2;
        }
    }
}
