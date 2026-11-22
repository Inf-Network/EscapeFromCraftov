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

    /** Points a death costs at least (before kills), see onDeath. */
    private static final int DEATH_BASE = 22;
    /**
     * Most points an extraction at a loss costs: below a death's, so walking out with less than
     * you brought is never scored worse than dying with it, or abandoning the raid.
     */
    private static final int EXTRACT_MAX_LOSS = DEATH_BASE - 2;

    /**
     * Points for getting out: one per ◈6,000 of profit (at most 45) plus kills, scaled by
     * difficulty, and one off per ◈6,000 lost (at most 20, not scaled). There is no flat bonus for
     * extracting: with one, walking out naked scored +18 against −22 for dying, so naked runs alone
     * climbed the ladder. Losses are halved below 1,100 points, as for deaths.
     */
    public int onExtract(Profile profile, RaidPlayer rp, long profit, Raid raid) {
        int gain = (int) Math.min(45, Math.max(0, profit) / 6000) + rp.kills * 6 + Math.min(10, rp.aiKills);
        gain = (int) Math.round(gain * (1 + raid.tier.lootShift() * 0.25));
        int loss = profit < 0 ? (int) Math.min(EXTRACT_MAX_LOSS, -profit / 6000) : 0;
        int delta = gain - loss;
        if (delta < 0 && profile.rankPoints < 1100) {
            delta = delta / 2;
        }
        profile.rankPoints = Math.max(0, profile.rankPoints + delta);
        return delta;
    }

    public int onDeath(Profile profile, RaidPlayer rp, long lost, Raid raid) {
        int delta = deathDelta(profile.rankPoints, rp, lost);
        profile.rankPoints = Math.max(0, profile.rankPoints + delta);
        return delta;
    }

    /** What a death costs a player at {@code rankPoints} (0 or less; points never drop below 0). */
    public int deathDelta(int rankPoints, RaidPlayer rp, long lost) {
        int delta = -(DEATH_BASE + (int) Math.min(30, lost / 20000)) + rp.kills * 5;
        if (rankPoints < 1100) {
            delta = delta / 2;
        }
        return Math.min(delta, 0);
    }

    public long xpForLevel(int level) {
        return Math.round(800 * Math.pow(level, 1.25));
    }

    public void addXp(Player player, Profile profile, long xp) {
        plugin.season().addXp(player, profile, xp);
        profile.xp += xp;
        while (profile.xp >= xpForLevel(profile.level) && profile.level < 60) {
            profile.xp -= xpForLevel(profile.level);
            profile.level++;
            long reward = 5000L * profile.level;
            profile.money += reward;
            Text.send(player, "<accent>账号升级至 Lv." + profile.level + "！<muted>奖励 ◈" + Text.money(reward));
            player.playSound(player.getLocation(), "craftov:ui.levelup", 1f, 1f);
        }
    }
}
