package net.infnetwork.snowball.craftov.economy;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.profile.ProfileService;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Bounties. Players put money on other players' heads from the lobby; the top-ranked player also
 * carries a standing bounty paid by the house, once a day, when they deploy for ranked with real
 * gear. Whoever kills a marked player in a raid collects all of it, and everyone in a raid is
 * told which marked players are in it.
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

    public long houseBounty() {
        return plugin.getConfig().getLong("bounty.champion", 20_000);
    }

    /** Gear the top player must deploy with for the house bounty to count. */
    public long houseMinValue() {
        return plugin.getConfig().getLong("bounty.champion-min-value", 60_000);
    }

    /** Everything on the player's head as the lobby shows it: placed bounties plus the house one if they are on top. */
    public long total(UUID target) {
        long house = target.equals(champion()) ? houseBounty() : 0;
        return placed(target) + house;
    }

    /**
     * Bounties players placed on {@code target}. Read without caching: the raid-start announcement
     * also asks about players who have disconnected since, and get() would keep their profile in
     * memory as if they were online (for an unreadable file, a stand-in that their next login would
     * then play on unsaved).
     */
    private long placed(UUID target) {
        Profile profile = plugin.profiles().peek(target);
        return profile == null ? 0 : profile.bounty;
    }

    private long houseBountyDay(UUID target) {
        Profile profile = plugin.profiles().peek(target);
        return profile == null ? -1 : profile.houseBountyDay;
    }

    /**
     * What killing {@code rp} pays in this raid. Placed bounties always count. The house bounty is
     * new money, so it only counts when the top player queued for ranked (and so also loses points
     * for the death), deployed with at least bounty.champion-min-value of gear, and once a day:
     * otherwise the top player could walk into a casual raid naked and have a friend shoot them
     * for ◈20,000 every few minutes.
     */
    public long inRaid(RaidPlayer rp) {
        return placed(rp.uuid) + (houseApplies(rp) ? houseBounty() : 0);
    }

    private boolean houseApplies(RaidPlayer rp) {
        return rp.ranked && rp.gearValue >= houseMinValue() && rp.uuid.equals(champion())
                && houseBountyDay(rp.uuid) != today();
    }

    /** Bounty day: rolls over at 04:00 like the daily contracts. */
    private static long today() {
        return LocalDateTime.now().minusHours(4).toLocalDate().toEpochDay();
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
        plugin.profiles().edit(target, () -> "bounty placed by " + by.getName() + ": bounty +" + amount, p -> p.bounty += amount);
        plugin.profiles().markDirty(by.getUniqueId());
        Player marked = Bukkit.getPlayer(target);
        if (marked != null) {
            Notify.info(marked, "<danger>有人悬赏了你的人头，<muted>现在共计 <accent>◈" + Text.money(total(target)) + "<muted>。");
        }
        return null;
    }

    /** Raid start: everyone hears who carries a price in this raid. */
    public void announce(Raid raid) {
        StringBuilder targets = new StringBuilder();
        for (RaidPlayer rp : raid.players.values()) {
            long amount = inRaid(rp);
            if (amount > 0) {
                targets.append(targets.isEmpty() ? "" : "  ").append("<white>").append(Text.escape(rp.name))
                        .append(" <accent>◈").append(Text.money(amount));
                Player marked = Bukkit.getPlayer(rp.uuid);
                if (marked != null) {
                    Notify.info(marked, "<danger>你身上有 <accent>◈" + Text.money(amount) + " <danger>的悬赏，所有人都知道你在这局。");
                }
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        for (Player player : raid.online()) {
            player.sendMessage(Text.mm("<danger>☠ 本局悬赏目标：" + targets));
        }
    }

    /**
     * A player killed another in a raid: pays out any bounty on the victim. The victim may be
     * offline (their stand-in was shot), so they are named by their raid record.
     */
    public void onKill(Player killer, RaidPlayer rp, Raid raid) {
        boolean house = houseApplies(rp);
        long placed = placed(rp.uuid);
        long dayBefore = houseBountyDay(rp.uuid);
        long amount = inRaid(rp);
        if (amount <= 0) {
            return;
        }
        long day = today();
        plugin.profiles().edit(rp.uuid,
                () -> "bounty claimed by " + killer.getName() + ": bounty = 0" + (house ? ", houseBountyDay = " + day : ""),
                p -> {
                    p.bounty = 0;
                    if (house) {
                        p.houseBountyDay = day;
                    }
                });
        Profile profile = plugin.profiles().get(killer);
        profile.money += amount;
        profile.earnedInRaid(amount);
        if (profile.deployment != null) {
            // Should the killer's raid turn out void, the kill never happened: the money goes back
            // on the victim's head (RaidManager.voidRaid).
            Profile.BountyClaim claim = new Profile.BountyClaim();
            claim.victim = rp.uuid;
            claim.placed = placed;
            claim.house = house;
            claim.dayBefore = dayBefore;
            claim.day = day;
            profile.deployment.bounties.add(claim);
        }
        plugin.profiles().markDirty(killer.getUniqueId());
        killer.playSound(killer.getLocation(), "craftov:ui.sell", SoundCategory.MASTER, 1f, 1f);
        for (Player player : raid.online()) {
            player.sendMessage(Text.mm("<accent>☠ " + Text.escape(killer.getName()) + " 领取了 " + Text.escape(rp.name)
                    + " 的悬赏 ◈" + Text.money(amount)));
        }
    }
}
