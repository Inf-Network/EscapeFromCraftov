package net.infnetwork.snowball.craftov.rank;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Season pass: forty tiers earned with the same experience as the account level. Every tier has a
 * reward (money, medicine, attachments, and a gun every tenth tier) claimed from the season menu.
 * Changing season.id in the config starts a new season and resets everyone's progress.
 */
public final class SeasonService {

    public static final int TIERS = 40;

    /** One tier's reward: money, or an item and amount. */
    public record Reward(long money, @Nullable String item, int amount) {
    }

    private static final String[] GUNS = {"k47", "m4c", "scr", "dm14"};
    private static final String[] PARTS = {"att_vgrip", "att_compensator", "att_taclight", "att_extmag", "att_suppressor",
            "optic_holo", "att_vgrip", "optic_3x"};
    private static final String[] MEDS = {"medkit_s", "hemostat", "painkiller", "medkit_l", "adrenaline", "surgery"};

    private final CraftovPlugin plugin;

    public SeasonService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public String id() {
        return plugin.getConfig().getString("season.id", "S1");
    }

    public String name() {
        return plugin.getConfig().getString("season.name", "第一赛季");
    }

    public long xpPerTier() {
        return Math.max(100, plugin.getConfig().getLong("season.xp-per-tier", 1000));
    }

    /** The profile's season state, reset if it belongs to an older season. */
    public Profile sync(Profile profile) {
        if (!id().equals(profile.seasonId)) {
            profile.seasonId = id();
            profile.seasonXp = 0;
            profile.seasonClaimed.clear();
        }
        return profile;
    }

    public int tier(Profile profile) {
        return (int) Math.min(TIERS, sync(profile).seasonXp / xpPerTier());
    }

    public static Reward reward(int tier) {
        if (tier % 10 == 0) {
            return new Reward(0, GUNS[(tier / 10 - 1) % GUNS.length], 1);
        }
        if (tier % 5 == 0) {
            return new Reward(0, PARTS[(tier / 5 - 1) % PARTS.length], 1);
        }
        if (tier % 3 == 0) {
            return new Reward(0, MEDS[(tier / 3 - 1) % MEDS.length], tier < 15 ? 2 : 1);
        }
        return new Reward(3000 + 400L * tier, null, 0);
    }

    /** Called with every experience grant. */
    public void addXp(Player player, Profile profile, long xp) {
        sync(profile);
        int before = tier(profile);
        profile.seasonXp += xp;
        int after = tier(profile);
        if (after > before) {
            Text.send(player, "<#FFC23D>赛季通行证升至第 " + after + " 级！<#9AA0A6>到大厅菜单「赛季通行证」领取奖励。");
            player.playSound(player.getLocation(), "craftov:ui.levelup", SoundCategory.MASTER, 0.8f, 1.2f);
        }
    }

    /** Null on success, otherwise why not. */
    public @Nullable String claim(Player player, int tier) {
        Profile profile = sync(plugin.profiles().get(player));
        if (tier < 1 || tier > TIERS) {
            return "没有这一级";
        }
        if (tier > tier(profile)) {
            return "尚未解锁";
        }
        if (profile.seasonClaimed.contains(tier)) {
            return "已经领取过了";
        }
        Reward reward = reward(tier);
        if (reward.item() != null) {
            ItemDef def = plugin.registry().get(reward.item());
            if (def == null) {
                return "奖励物品不存在";
            }
            profile.mailbox.add(new ItemRecord(def.id(), reward.amount()));
        } else {
            profile.money += reward.money();
        }
        profile.seasonClaimed.add(tier);
        plugin.profiles().markDirty(player.getUniqueId());
        return null;
    }
}
