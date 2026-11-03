package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.rank.CollectionService;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;

import java.util.List;

final class CollectionMenu extends Menu {

    private final CraftovPlugin plugin;

    CollectionMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 4, Text.mm("<#F2E6C8>收藏室"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        Profile p = plugin.profiles().get(viewer);
        int slot = 0;
        for (ItemDef def : plugin.collection().all()) {
            boolean owned = p.collected.contains(def.id());
            if (owned) {
                set(slot++, plugin.items().create(def, 1));
            } else {
                set(slot++, UiItems.button("craftov:ui/collection_unknown", "<#6B7079>??? <#8A8F98>(" + def.rarity().displayName() + ")",
                        List.of("<#8A8F98>在行动中找到并成功撤离以收录")));
            }
        }
        fillRow(3, UiItems.filler());
        int percent = plugin.collection().percent(p);
        set(31, UiItems.button("craftov:ui/icon_collection", "<#FF4D4D><b>完成度 " + percent + "%", List.of(
                "<#8A8F98>25% 奖励 ◈" + Text.money(CollectionService.REWARDS[0]) + mark(p, 25),
                "<#8A8F98>50% 奖励 ◈" + Text.money(CollectionService.REWARDS[1]) + mark(p, 50),
                "<#8A8F98>75% 奖励 ◈" + Text.money(CollectionService.REWARDS[2]) + mark(p, 75),
                "<#8A8F98>100% 奖励 ◈" + Text.money(CollectionService.REWARDS[3]) + mark(p, 100))));
    }

    private static String mark(Profile p, int milestone) {
        return p.collectionRewards.contains(milestone) ? " <#5BD16B>✔" : "";
    }
}
