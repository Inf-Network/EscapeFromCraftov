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
        super(viewer, 3, Text.mm("<#F2E6C8>干员"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        String current = plugin.operators().of(viewer).id();
        int slot = 10;
        for (OperatorDef def : OperatorService.OPERATORS.values()) {
            List<String> lore = new ArrayList<>();
            lore.add("<#8A8F98>定位 <white>" + def.role());
            lore.add("<#8A8F98>技能 <" + def.color() + ">" + def.skillName() + " <#8A8F98>(冷却 " + def.cooldownSeconds() + "s)");
            for (String line : def.description()) {
                lore.add("<#9AA0A6>" + line);
            }
            lore.add(def.id().equals(current) ? "<#5BD16B>当前干员" : "<#8A8F98>点击选择");
            button(slot, UiItems.button(def.icon(), (def.id().equals(current) ? "<#F2C94C>▶ " : "") + "<" + def.color() + "><b>" + def.name(), lore), e -> {
                plugin.operators().select(viewer, def.id());
                redraw();
            });
            slot += 2;
        }
    }
}
