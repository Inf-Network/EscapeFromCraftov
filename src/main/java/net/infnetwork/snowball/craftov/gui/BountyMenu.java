package net.infnetwork.snowball.craftov.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounty board: online players and the price on their heads; click to add to it. */
final class BountyMenu extends Menu {

    private final CraftovPlugin plugin;

    BountyMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 4, Text.mm("<text>悬赏榜"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        UUID champion = plugin.bounties().champion();
        set(4, UiItems.button("craftov:ui/icon_bounty", "<danger><b>悬赏榜", List.of(
                "<muted>点击玩家头像，花 ◈" + Text.money(plugin.bounties().step()) + " 追加悬赏",
                "<muted>在行动中击杀被悬赏的玩家即可领取全部赏金",
                "<muted>排位第一的玩家自带 ◈" + Text.money(plugin.bounties().houseBounty()) + " 系统悬赏",
                "<muted>（只在排位赛、其战备值不低于 ◈" + Text.money(plugin.bounties().houseMinValue()) + " 时有效，每天一次）")));
        int slot = 9;
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort((a, b) -> Long.compare(plugin.bounties().total(b.getUniqueId()), plugin.bounties().total(a.getUniqueId())));
        for (Player target : online) {
            if (slot >= 36) {
                break;
            }
            long total = plugin.bounties().total(target.getUniqueId());
            ItemStack head = ItemStack.of(Material.PLAYER_HEAD);
            head.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(target.getPlayerProfile()));
            head.setData(DataComponentTypes.ITEM_NAME, Text.mm((target.getUniqueId().equals(champion) ? "<accent>♛ " : "<white>")
                    + Text.escape(target.getName())));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(Text.mm(total > 0 ? "<accent>悬赏 ◈" + Text.money(total) : "<muted>暂无悬赏"));
            if (!target.equals(viewer)) {
                lore.add(Text.mm("<muted>点击追加 ◈" + Text.money(plugin.bounties().step())));
            }
            head.setData(DataComponentTypes.LORE, io.papermc.paper.datacomponent.item.ItemLore.lore(lore));
            button(slot++, head, e -> {
                String why = plugin.bounties().place(viewer, target.getUniqueId());
                viewer.sendActionBar(Text.mm(why == null ? "<ok>已追加悬赏" : "<danger>" + why));
                if (why == null) {
                    viewer.playSound(viewer.getLocation(), "craftov:ui.buy", SoundCategory.MASTER, 0.8f, 1f);
                }
                redraw();
            });
        }
    }
}
