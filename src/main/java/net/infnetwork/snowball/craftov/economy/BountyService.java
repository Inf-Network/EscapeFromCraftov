package net.infnetwork.snowball.craftov.economy;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.profile.ProfileService;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Bounties. Players put money on other players' heads from the lobby; the top-ranked player also
 * carries a standing bounty paid by the house. Whoever kills a marked player in a raid collects all
 * of it, and everyone in a raid is told which marked players are in it.
 */
public final class BountyService {

    private final CraftovPlugin plugin;

    public BountyService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public long step() {
        return plugin.getConfig().getLong("bounty.step", 10_000);
    }

    /** The top-ranked player, who carries the house bounty, or null. */
    public @Nullable UUID champion() {
        List<ProfileService.Summary> top = plugin.profiles().leaderboard((a, b) -> Integer.compare(b.rankPoints(), a.rankPoints()), 1);
        return top.isEmpty() ? null : top.get(0).uuid();
    }

    public long total(UUID target) {
        long placed = plugin.profiles().get(target, "").bounty;
        long house = target.equals(champion()) ? plugin.getConfig().getLong("bounty.champion", 20_000) : 0;
        return placed + house;
    }

    /** Null on success, otherwise why not. */
    public @Nullable String place(Player by, UUID target) {
        if (by.getUniqueId().equals(target)) {
            return "不能悬赏自己";
        }
        Profile payer = plugin.profiles().get(by);
        long amount = step();
        if (payer.money < amount) {
            return "块币不足";
        }
        payer.money -= amount;
        plugin.profiles().edit(target, p -> p.bounty += amount);
        plugin.profiles().markDirty(by.getUniqueId());
        Player marked = Bukkit.getPlayer(target);
        if (marked != null) {
            Text.send(marked, "<#FF4D4D>有人悬赏了你的人头，<#9AA0A6>现在共计 <#F2C94C>◈" + Text.money(total(target)) + "<#9AA0A6>。");
        }
        return null;
    }

    /** Raid start: everyone hears who carries a price in this raid. */
    public void announce(Raid raid) {
        StringBuilder targets = new StringBuilder();
        for (RaidPlayer rp : raid.players.values()) {
            long amount = total(rp.uuid);
            if (amount > 0) {
                targets.append(targets.isEmpty() ? "" : "  ").append("<white>").append(Text.escape(rp.name))
                        .append(" <#F2C94C>◈").append(Text.money(amount));
                Player marked = Bukkit.getPlayer(rp.uuid);
                if (marked != null) {
                    Text.send(marked, "<#FF4D4D>你身上有 <#F2C94C>◈" + Text.money(amount) + " <#FF4D4D>的悬赏，所有人都知道你在这局。");
                }
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        for (Player player : raid.online()) {
            player.sendMessage(Text.mm("<#FF4D4D>☠ 本局悬赏目标：" + targets));
        }
    }

    /** A player killed another in a raid: pays out any bounty on the victim. */
    public void onKill(Player killer, Player victim, Raid raid) {
        long amount = total(victim.getUniqueId());
        if (amount <= 0) {
            return;
        }
        plugin.profiles().edit(victim.getUniqueId(), p -> p.bounty = 0);
        Profile profile = plugin.profiles().get(killer);
        profile.money += amount;
        plugin.profiles().markDirty(killer.getUniqueId());
        killer.playSound(killer.getLocation(), "craftov:ui.sell", SoundCategory.MASTER, 1f, 1f);
        for (Player player : raid.online()) {
            player.sendMessage(Text.mm("<#F2C94C>☠ " + Text.escape(killer.getName()) + " 领取了 " + Text.escape(victim.getName())
                    + " 的悬赏 ◈" + Text.money(amount)));
        }
    }
}
