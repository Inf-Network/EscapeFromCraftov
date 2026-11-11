package net.infnetwork.snowball.craftov.task;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.map.MapDef;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Daily contracts: three objectives per player, refreshed at 04:00 server time. Progress counts the
 * moment it happens (kills and searches stay counted even if the raid is lost); extraction goals
 * count on a successful extraction. Rewards are paid on completion. An unfinished contract can be
 * swapped for a new one for a fee.
 */
public final class TaskService {

    public enum Kind {
        AI_KILL("击败敌兵"),
        HEADSHOT("爆头击败敌兵"),
        MELEE_KILL("用近战武器击败敌兵"),
        BOSS_KILL("击败首领"),
        PLAYER_KILL("击败其他干员"),
        SEARCH("首次搜索物资箱"),
        EXTRACT("成功撤离"),
        EXTRACT_MAP("从指定地图撤离"),
        EXTRACT_VALUE("单局净收益达到");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public static final int COUNT = 3;

    private final CraftovPlugin plugin;

    public TaskService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public long rerollCost() {
        return plugin.getConfig().getLong("tasks.reroll-cost", 3000);
    }

    /** Contract day: rolls over at 04:00 so late-night sessions keep their contracts. */
    private static long today() {
        return LocalDateTime.now().minusHours(4).toLocalDate().toEpochDay();
    }

    /** The player's contracts for today, generating a fresh set after the daily reset. */
    public List<Profile.TaskState> of(Profile profile) {
        long day = today();
        if (profile.taskDay != day || profile.tasks.size() != COUNT) {
            profile.taskDay = day;
            profile.tasks.clear();
            Set<Kind> used = EnumSet.noneOf(Kind.class);
            for (int i = 0; i < COUNT; i++) {
                Profile.TaskState task = roll(used);
                used.add(Kind.valueOf(task.kind));
                profile.tasks.add(task);
            }
        }
        return profile.tasks;
    }

    /** Swaps an unfinished contract for a different one. */
    public boolean reroll(Player player, int index) {
        Profile profile = plugin.profiles().get(player);
        List<Profile.TaskState> tasks = of(profile);
        if (index < 0 || index >= tasks.size() || tasks.get(index).done) {
            return false;
        }
        long cost = rerollCost();
        if (profile.money < cost) {
            Text.send(player, "<#FF8A80>块币不足，更换任务需要 ◈" + Text.money(cost));
            return false;
        }
        profile.money -= cost;
        Set<Kind> used = EnumSet.noneOf(Kind.class);
        for (Profile.TaskState t : tasks) {
            used.add(Kind.valueOf(t.kind));
        }
        tasks.set(index, roll(used));
        plugin.profiles().markDirty(player.getUniqueId());
        return true;
    }

    private Profile.TaskState roll(Set<Kind> exclude) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<Kind> pool = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            if (!exclude.contains(kind) && (kind != Kind.EXTRACT_MAP || !raidMaps().isEmpty())) {
                pool.add(kind);
            }
        }
        Kind kind = pool.get(rng.nextInt(pool.size()));
        Profile.TaskState task = new Profile.TaskState();
        task.kind = kind.name();
        switch (kind) {
            case AI_KILL -> { task.target = 5 + rng.nextInt(8); task.reward = 1500L * task.target; }
            case HEADSHOT -> { task.target = 3 + rng.nextInt(4); task.reward = 3000L * task.target; }
            case MELEE_KILL -> { task.target = 1 + rng.nextInt(2); task.reward = 9000L * task.target; }
            case BOSS_KILL -> { task.target = 1; task.reward = 45000; }
            case PLAYER_KILL -> { task.target = 1 + rng.nextInt(2); task.reward = 16000L * task.target; }
            case SEARCH -> { task.target = 8 + rng.nextInt(10); task.reward = 900L * task.target; }
            case EXTRACT -> { task.target = 1 + rng.nextInt(3); task.reward = 7000L * task.target; }
            case EXTRACT_MAP -> {
                List<MapDef> maps = raidMaps();
                MapDef map = maps.get(rng.nextInt(maps.size()));
                task.map = map.id();
                task.mapName = map.name();
                task.target = 1;
                task.reward = 12000;
            }
            case EXTRACT_VALUE -> {
                task.target = (3 + rng.nextInt(10)) * 10000;
                task.reward = Math.round(task.target * 0.3);
            }
        }
        task.xp = 300 + task.reward / 40;
        return task;
    }

    private List<MapDef> raidMaps() {
        List<MapDef> maps = new ArrayList<>();
        for (MapDef map : plugin.maps().all()) {
            if (!map.spawns().isEmpty()) {
                maps.add(map);
            }
        }
        return maps;
    }

    public static String describe(Profile.TaskState task) {
        Kind kind = Kind.valueOf(task.kind);
        return switch (kind) {
            case EXTRACT_MAP -> "从「" + (task.mapName == null ? task.map : task.mapName) + "」成功撤离";
            case EXTRACT_VALUE -> "单局净收益达到 ◈" + Text.money(task.target);
            default -> kind.label + " " + task.target + (kind == Kind.EXTRACT ? " 次" : kind == Kind.SEARCH ? " 个" : " 名");
        };
    }

    public static String progressText(Profile.TaskState task) {
        if (Kind.valueOf(task.kind) == Kind.EXTRACT_VALUE) {
            return task.done ? "已完成" : "未完成";
        }
        return Math.min(task.progress, task.target) + "/" + task.target;
    }

    // ------------------------------------------------------------------ progress hooks

    public void onAiKill(Player killer, boolean boss, boolean headshot, boolean melee) {
        add(killer, Kind.AI_KILL, 1, null);
        if (headshot) {
            add(killer, Kind.HEADSHOT, 1, null);
        }
        if (melee) {
            add(killer, Kind.MELEE_KILL, 1, null);
        }
        if (boss) {
            add(killer, Kind.BOSS_KILL, 1, null);
        }
    }

    public void onPlayerKill(Player killer) {
        add(killer, Kind.PLAYER_KILL, 1, null);
    }

    public void onSearch(Player player) {
        add(player, Kind.SEARCH, 1, null);
    }

    public void onExtract(Player player, Raid raid, long profit) {
        add(player, Kind.EXTRACT, 1, null);
        add(player, Kind.EXTRACT_MAP, 1, raid.map.id());
        Profile profile = plugin.profiles().get(player);
        for (Profile.TaskState task : of(profile)) {
            if (!task.done && Kind.valueOf(task.kind) == Kind.EXTRACT_VALUE && profit >= task.target) {
                task.progress = task.target;
                complete(player, profile, task);
            }
        }
    }

    private void add(Player player, Kind kind, int amount, @Nullable String map) {
        Profile profile = plugin.profiles().get(player);
        for (Profile.TaskState task : of(profile)) {
            if (task.done || Kind.valueOf(task.kind) != kind || (task.map != null && !task.map.equals(map))) {
                continue;
            }
            task.progress = Math.min(task.target, task.progress + amount);
            if (task.progress >= task.target) {
                complete(player, profile, task);
            } else if (kind != Kind.SEARCH || task.progress % 5 == 0) {
                player.sendMessage(Text.mm("<#8A8F98>[每日任务] " + describe(task) + " <#F2C94C>" + progressText(task)));
            }
            plugin.profiles().markDirty(player.getUniqueId());
        }
    }

    private void complete(Player player, Profile profile, Profile.TaskState task) {
        task.done = true;
        profile.money += task.reward;
        profile.addStat("tasks_done", 1);
        plugin.ranks().addXp(player, profile, task.xp);
        plugin.profiles().markDirty(player.getUniqueId());
        player.sendMessage(Text.mm("<#5BD16B><b>[每日任务完成]</b> <white>" + describe(task)
                + " <#F2C94C>+◈" + Text.money(task.reward) + " <#8A8F98>+" + task.xp + " 经验"));
        player.playSound(player.getLocation(), "minecraft:entity.player.levelup", SoundCategory.PLAYERS, 0.7f, 1.4f);
        boolean all = true;
        for (Profile.TaskState t : profile.tasks) {
            all &= t.done;
        }
        if (all) {
            long bonus = plugin.getConfig().getLong("tasks.all-done-bonus", 20000);
            profile.money += bonus;
            player.sendMessage(Text.mm("<#FFC23D><b>今日任务全部完成！</b> <#F2C94C>额外奖励 ◈" + Text.money(bonus)));
        }
    }
}
