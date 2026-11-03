package net.infnetwork.snowball.craftov.rank;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Collection book: the first extraction of each collectible is recorded and rewarded. */
public final class CollectionService {

    public static final int[] MILESTONES = {25, 50, 75, 100};
    public static final long[] REWARDS = {100_000, 300_000, 800_000, 2_000_000};

    private final CraftovPlugin plugin;

    public CollectionService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public List<ItemDef> all() {
        return plugin.registry().byCategory(Category.COLLECTIBLE);
    }

    public int percent(Profile profile) {
        int total = all().size();
        if (total == 0) {
            return 0;
        }
        int owned = 0;
        for (ItemDef def : all()) {
            if (profile.collected.contains(def.id())) {
                owned++;
            }
        }
        return owned * 100 / total;
    }

    /** Returns the names of newly registered collectibles. */
    public List<String> registerExtraction(Player player, Profile profile, Raid raid) {
        List<ItemRecord> carried = new ArrayList<>(plugin.carry().allRecords(player));
        RaidPlayer rp = raid.player(player.getUniqueId());
        if (rp != null) {
            carried.addAll(rp.secure);
        }
        List<String> fresh = new ArrayList<>();
        collect(carried, profile, fresh);
        if (!fresh.isEmpty()) {
            for (String name : fresh) {
                Bukkit.broadcast(Text.mm(Text.PREFIX + "<#FF4D4D>★ <white>" + Text.escape(player.getName())
                        + "</white> 成功带出收藏品 <#FF4D4D>" + Text.escape(name) + "</#FF4D4D>！"));
            }
            checkMilestones(player, profile);
        }
        return fresh;
    }

    private void collect(List<ItemRecord> records, Profile profile, List<String> fresh) {
        for (ItemRecord record : records) {
            ItemDef def = plugin.registry().get(record.id);
            if (def != null && def.category() == Category.COLLECTIBLE && profile.collected.add(def.id())) {
                fresh.add(def.name());
            }
            if (record.contents != null) {
                collect(record.contents, profile, fresh);
            }
        }
    }

    private void checkMilestones(Player player, Profile profile) {
        int percent = percent(profile);
        for (int i = 0; i < MILESTONES.length; i++) {
            if (percent >= MILESTONES[i] && profile.collectionRewards.add(MILESTONES[i])) {
                profile.money += REWARDS[i];
                Text.send(player, "<#FFC23D>收藏室完成度达到 " + MILESTONES[i] + "%！奖励 ◈" + Text.money(REWARDS[i]));
            }
        }
    }
}
