package net.infnetwork.snowball.craftov.lobby;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.gui.UiSound;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * First steps for a new player: open the terminal, try a gun on the range, fit an attachment at the
 * gunsmith, deploy, and extract. Steps count in any order: the guide always points at the first one
 * not done yet, and the first extraction ends it with the starter bonus whatever was skipped on the
 * way (an extraction is what the guide is there to teach). Hints say what to do where the player
 * is: on the range the gunsmith is behind the weapon rack, and in a raid only the extraction can be
 * done, so that is all a raid ever reminds of. Players who extracted before the guide existed are
 * veterans and never see it; whoever turns the guide's pointers off keeps the reward for the first
 * extraction but gets no reminders.
 */
public final class TutorialService {

    public enum Step {
        TERMINAL("打开战术终端",
                "选中快捷栏第 9 格的<white>战术终端</white>（按 <white><key:key.hotbar.9></white>），右键打开大厅菜单"),
        RANGE("进入靶场",
                "到大厅东侧的<white>靶场武器架</white>，右键进入个人靶场试枪"),
        GUNSMITH("装一个配件",
                "进入靶场，在武器架领一把枪和一个瞄准镜，再点武器架里的<white>改枪台</white>装上"),
        DEPLOY("出发",
                "右键大厅的<white>开始行动</white>（或终端里的大按钮），选一张地图出发"),
        EXTRACT("活着撤离",
                "开始行动，搜刮物资后找到<white>撤离点</white>，活着撤离");

        /** Short name: the menu checklist and "next step" lines. */
        public final String title;
        /** The full hint in chat and on the action bar (MiniMessage). */
        public final String hint;

        Step(String title, String hint) {
            this.title = title;
            this.hint = hint;
        }

        int bit() {
            return 1 << ordinal();
        }
    }

    private final CraftovPlugin plugin;

    public TutorialService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::remind, 20L * 30, 20L * 45);
    }

    // ------------------------------------------------------------------ state

    private boolean enabled() {
        return plugin.getConfig().getBoolean("tutorial.enabled", true);
    }

    /**
     * The steps done, as bits. Profiles saved by the in-order guide only have its counter, which
     * stands for that many steps from the first; read here without writing, so a getter on the HUD
     * thread never changes the profile.
     */
    public static int steps(Profile profile) {
        if (profile.tutorialSteps != 0 || profile.tutorialStep <= 0) {
            return profile.tutorialSteps;
        }
        return (1 << Math.min(profile.tutorialStep, Step.values().length)) - 1;
    }

    public static boolean isDone(Profile profile, Step step) {
        return (steps(profile) & step.bit()) != 0;
    }

    /** A player who extracted before the guide began counting anything has nothing to learn from it. */
    private static boolean veteran(Profile profile) {
        return steps(profile) == 0 && profile.stat("extracts") > 0;
    }

    /** The guide is running for this player (whether or not they muted its pointers). */
    public boolean active(Profile profile) {
        return enabled() && !profile.tutorialDone && !veteran(profile);
    }

    /** The guide is running and pointing (reminders). */
    public boolean guided(Profile profile) {
        return active(profile) && !profile.guideMuted;
    }

    /** The first step not done yet, or null when all are. */
    public static @Nullable Step current(Profile profile) {
        int mask = steps(profile);
        for (Step step : Step.values()) {
            if ((mask & step.bit()) == 0) {
                return step;
            }
        }
        return null;
    }

    public static int doneCount(Profile profile) {
        return Integer.bitCount(steps(profile) & ((1 << Step.values().length) - 1));
    }

    /** Marks a step done, whatever came before it; the extraction finishes the guide. */
    public void done(Player player, Step step) {
        Profile profile = plugin.profiles().get(player);
        if (!active(profile) || isDone(profile, step)) {
            return;
        }
        profile.tutorialSteps = steps(profile) | step.bit();
        plugin.profiles().markDirty(player.getUniqueId());
        if (step == Step.EXTRACT) {
            finish(player, profile);
            return;
        }
        player.playSound(player.getLocation(), "craftov:ui.queue", org.bukkit.SoundCategory.MASTER, 0.7f, 1.3f);
        if (profile.guideMuted) {
            return;
        }
        Step next = current(profile);
        // The action bar, not chat: on the range the entry line is in chat already, and a step done
        // with a click in a menu (the gunsmith) shows on that button.
        Notify.ok(player, "新手指引 " + doneCount(profile) + "/" + Step.values().length + " · " + step.title
                + (next == null ? "" : " <muted>· 下一步：<text>" + next.title));
    }

    /** The guide is over: the starter bonus, once. */
    public void finish(Player player, Profile profile) {
        if (profile.tutorialDone) {
            return;
        }
        profile.tutorialDone = true;
        long bonus = plugin.getConfig().getLong("tutorial.reward", 20_000);
        profile.money += bonus;
        plugin.profiles().markDirty(player.getUniqueId());
        UiSound.REWARD.play(player);
        Text.send(player, "<ok><b>新手指引完成！</b> <accent>奖励 ◈" + Text.money(bonus)
                + " <muted>· 之后可以试试每日任务、藏身处和排位赛。");
    }

    /** The player turned the guide's pointers off or back on. */
    public void mute(Player player, boolean muted) {
        Profile profile = plugin.profiles().get(player);
        profile.guideMuted = muted;
        profile.briefed = true;
        plugin.profiles().markDirty(player.getUniqueId());
    }

    // ------------------------------------------------------------------ hints

    /** The hint for a step where the player is now. */
    public String hint(Player player, Step step) {
        if (step == Step.GUNSMITH && plugin.range().isIn(player)) {
            return "在<white>武器架</white>领一把枪和一个瞄准镜，再点武器架里的<white>改枪台</white>装上";
        }
        if (step == Step.EXTRACT && plugin.raids().raidOf(player) != null) {
            return "找到<white>撤离点</white>（罗盘上的绿色 ◆，左上角有方向和距离），站进去等倒计时结束";
        }
        return step.hint;
    }

    private String line(Profile profile, String hint) {
        return "<accent>新手指引 " + (doneCount(profile) + 1) + "/" + Step.values().length + "：<text>" + hint;
    }

    /** Back in the lobby: the step to do next, in chat. Players who extracted before are let go here. */
    public void show(Player player, Profile profile) {
        if (!profile.tutorialDone && veteran(profile)) {
            profile.tutorialDone = true;
            plugin.profiles().markDirty(player.getUniqueId());
            return;
        }
        Step step = current(profile);
        if (!guided(profile) || step == null) {
            return;
        }
        Text.send(player, line(profile, hint(player, step)));
    }

    /**
     * Every 45 seconds, the step on the action bar: in the lobby and on the range the next step, in a
     * raid only the extraction (nothing else can be done there), and nothing while queueing (the
     * queue timer has the bar) or watching a squadmate.
     */
    private void remind() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Profile profile = plugin.profiles().get(player);
            if (!guided(profile) || plugin.match().queued(player.getUniqueId()) != null) {
                continue;
            }
            Step step;
            if (plugin.raids().raidOf(player) != null) {
                if (!plugin.raids().isInRaid(player) || isDone(profile, Step.EXTRACT)) {
                    continue;
                }
                step = Step.EXTRACT;
            } else if (plugin.lobby().isLobby(player.getWorld()) || plugin.range().isIn(player)) {
                step = current(profile);
            } else {
                continue;
            }
            if (step != null) {
                Notify.hint(player, line(profile, hint(player, step)));
            }
        }
    }
}
