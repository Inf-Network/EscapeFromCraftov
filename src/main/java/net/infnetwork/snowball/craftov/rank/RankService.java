package net.infnetwork.snowball.craftov.rank;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;

/** Ranked points, rank tiers and account levels. */
public final class RankService {

    private static final int[] THRESHOLDS = {0, 1100, 1250, 1400, 1550, 1700, 1850};
    private static final String[] NAMES = {"青铜", "白银", "黄金", "铂金", "钻石", "大师", "传奇"};
    private static final String[] COLORS = {"#B08D57", "#C8CCD2", "#FFC23D", "#7FE0D0", "#4FA3FF", "#B66BFF", "#FF4D4D"};

    private final CraftovPlugin plugin;

    public RankService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public int tierIndex(int points) {
        int index = 0;
        for (int i = 0; i < THRESHOLDS.length; i++) {
            if (points >= THRESHOLDS[i]) {
                index = i;
            }
        }
        return index;
    }

    public String tierName(int points) {
        int i = tierIndex(points);
        String division = "";
        if (i < THRESHOLDS.length - 1) {
            int span = THRESHOLDS[i + 1] - THRESHOLDS[i];
            int into = points - THRESHOLDS[i];
            division = " " + (3 - Math.min(2, into * 3 / Math.max(1, span)));
        }
        return "<" + COLORS[i] + ">" + NAMES[i] + division + "</" + COLORS[i] + ">";
    }

    public int onExtract(Profile profile, RaidPlayer rp, long profit, Raid raid) {
        int delta = 18 + (int) Math.min(45, Math.max(0, profit) / 6000) + rp.kills * 6 + Math.min(10, rp.aiKills);
        delta = (int) Math.round(delta * (1 + raid.tier.lootShift() * 0.25));
        profile.rankPoints += delta;
        return delta;
    }

    public int onDeath(Profile profile, RaidPlayer rp, long lost, Raid raid) {
        int delta = -(22 + (int) Math.min(30, lost / 20000)) + rp.kills * 5;
        if (profile.rankPoints < 1100) {
            delta = delta / 2;
        }
        delta = Math.min(delta, 0);
        profile.rankPoints = Math.max(0, profile.rankPoints + delta);
        return delta;
    }

    public long xpForLevel(int level) {
        return Math.round(800 * Math.pow(level, 1.25));
    }

    public void addXp(Player player, Profile profile, long xp) {
        profile.xp += xp;
        while (profile.xp >= xpForLevel(profile.level) && profile.level < 60) {
            profile.xp -= xpForLevel(profile.level);
            profile.level++;
            long reward = 5000L * profile.level;
            profile.money += reward;
            Text.send(player, "<#FFC23D>账号升级至 Lv." + profile.level + "！<#9AA0A6>奖励 ◈" + Text.money(reward));
            player.playSound(player.getLocation(), "craftov:ui.levelup", 1f, 1f);
        }
    }
}
