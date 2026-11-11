package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.rank.SeasonService;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Season pass: the forty tiers with their rewards; unlocked ones are claimed by clicking. */
final class SeasonMenu extends Menu {

    private final CraftovPlugin plugin;

    SeasonMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<#F2E6C8>赛季通行证"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        SeasonService season = plugin.season();
        Profile profile = season.sync(plugin.profiles().get(viewer));
        int tier = season.tier(profile);
        long into = profile.seasonXp - tier * season.xpPerTier();
        set(4, UiItems.button("craftov:ui/icon_rank", "<#FFC23D><b>" + Text.escape(season.name()) + " · 第 " + tier + " 级", List.of(
                tier >= SeasonService.TIERS ? "<#5BD16B>已满级" : "<#9AA0A6>下一级 " + into + "/" + season.xpPerTier() + " 经验",
                "<#9AA0A6>行动、击败敌人和完成任务获得的经验都会计入",
                "<#9AA0A6>物品奖励送到仓库邮箱")));
        for (int t = 1; t <= SeasonService.TIERS; t++) {
            SeasonService.Reward reward = SeasonService.reward(t);
            boolean claimed = profile.seasonClaimed.contains(t);
            boolean unlocked = t <= tier;
            ItemStack icon;
            String what;
            ItemDef def = reward.item() == null ? null : plugin.registry().get(reward.item());
            if (def != null) {
                icon = plugin.items().create(def, Math.max(1, reward.amount()));
                what = def.name() + (reward.amount() > 1 ? " ×" + reward.amount() : "");
            } else {
                icon = UiItems.button("craftov:ui/icon_value", "", List.of());
                what = "◈" + Text.money(reward.money());
            }
            if (icon == null) {
                continue;
            }
            String status = claimed ? "<#8A8F98>已领取" : unlocked ? "<#5BD16B>点击领取" : "<#8A8F98>未解锁";
            icon.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_NAME,
                    Text.mm((claimed ? "<#8A8F98>" : unlocked ? "<#5BD16B>" : "<#C8CCD2>") + "第 " + t + " 级 · " + Text.escape(what)));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(Text.mm(status));
            icon.setData(io.papermc.paper.datacomponent.DataComponentTypes.LORE, io.papermc.paper.datacomponent.item.ItemLore.lore(lore));
            if (claimed) {
                icon.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key("craftov", "ui/report_ok"));
            } else if (unlocked) {
                icon.setData(io.papermc.paper.datacomponent.DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            int t2 = t;
            button(9 + (t - 1), icon, e -> {
                String why = season.claim(viewer, t2);
                viewer.sendActionBar(Text.mm(why == null ? "<#5BD16B>已领取第 " + t2 + " 级奖励" : "<#FF8A80>" + why));
                if (why == null) {
                    viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", SoundCategory.MASTER, 0.8f, 1f);
                }
                redraw();
            });
        }
    }
}
