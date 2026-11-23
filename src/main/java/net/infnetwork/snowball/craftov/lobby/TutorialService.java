package net.infnetwork.snowball.craftov.lobby;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * First steps for a new player: open the terminal, try a gun on the range, check the client's FOV
 * Effects slider (every scope zooms through it), fit an attachment at the gunsmith, deploy, and
 * extract. Steps count in any order; the first one still open is pointed out until done, and
 * finishing all six pays a starter bonus. A player who had extracted before the guide existed never
 * sees it.
 */
public final class TutorialService {

    /**
     * The steps in the order they are pointed out. Each keeps its own bit in {@link Profile#tutorialMask}:
     * the first five bits are the old guide's steps in its order (which {@link #migrate} relies on), and
     * the FOV Effects step, added later, takes the next one, so saved masks keep their meaning.
     */
    public enum Step {
        TERMINAL(0, "右键快捷栏第 9 格的<white>战术终端</white>，打开大厅菜单"),
        RANGE(1, "到大厅东侧的<white>靶场入口</white>右键进入个人靶场，试一把枪"),
        FOV(5, "按 <white>Esc → 选项 → 辅助功能</white>，确认 <white>FOV 特效</white> 是 100%（瞄准镜靠它放大），再输入 <white>/cv fov ok</white>"),
        GUNSMITH(2, "打开<white>改枪台</white>（菜单或 /cv mod），给枪装上任意一个配件"),
        DEPLOY(3, "在菜单里点<white>开始行动</white>，选择地图出发"),
        EXTRACT(4, "在局内找到<white>撤离点</white>（战术终端可查看），活着撤离");

        private final int bit;
        public final String hint;

        Step(int bit, String hint) {
            this.bit = 1 << bit;
            this.hint = hint;
        }

        int bit() {
            return bit;
        }
    }

    private static final int ALL = allSteps();
    /** How many steps the old guide's counter ({@link Profile#tutorialStep}) counted in. */
    private static final int OLD_STEPS = 5;

    private static int allSteps() {
        int all = 0;
        for (Step step : Step.values()) {
            all |= step.bit();
        }
        return all;
    }

    private final CraftovPlugin plugin;

    public TutorialService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::remind, 20L * 30, 20L * 45);
    }

    /**
     * When the guide was released (75f7382b, 2026-11-11T13:17:51Z). A player whose first raid came
     * later began with the guide.
     */
    private static final long GUIDE_RELEASED = 1_794_403_071_000L;

    /**
     * Turns the old step counter of a saved profile into the set of finished steps: a counter at
     * {@code n} had the first {@code n} steps done. The old build also lost the guide for a new player
     * who extracted before reaching the extract step: their next step took them for a veteran and
     * closed it without the reward, and until then it stayed open but silent. Such a guide is opened
     * again with the deploy and the extract that broke it counted. Two kinds of profile show it:
     * <ul>
     *   <li>a counter at 1 to 4 and an extract: the old counter only moved while the player had no
     *       extract, so the extract came after the guide had begun;</li>
     *   <li>no counter, an extract, and every raid on record after the guide's release: a new player
     *       who deployed from the lobby station or {@code /cv deploy}, which skip the terminal step.
     *       The old builds kept a player's last 20 raid reports, so with fewer the first raid is among
     *       them, and a veteran's first raid came before the guide.</li>
     * </ul>
     * Called on load. A reopened guide has steps on record, so it never matches twice.
     */
    public static void migrate(Profile profile) {
        int steps = Math.clamp(profile.tutorialStep, 0, OLD_STEPS);
        profile.tutorialMask |= (1 << steps) - 1;
        profile.tutorialStep = 0;
        if (steps < OLD_STEPS && profile.stat("extracts") > 0
                && (steps > 0 || profile.tutorialMask == 0 && beganWithGuide(profile))) {
            profile.tutorialDone = false;
            profile.tutorialMask |= Step.DEPLOY.bit() | Step.EXTRACT.bit();
        }
    }

    private static boolean beganWithGuide(Profile profile) {
        if (profile.reports.isEmpty() || profile.reports.size() >= 20) {
            return false;
        }
        for (Profile.RaidReport report : profile.reports) {
            if (report == null || report.time < GUIDE_RELEASED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the guide still runs for this player. Someone who has extracted without a single step
     * on record extracted before the guide existed (every extract counts as a step before it counts
     * as an extract), so the guide closes for them, once and without a reward.
     */
    private boolean active(Player player, Profile profile) {
        if (profile.tutorialDone || !plugin.getConfig().getBoolean("tutorial.enabled", true)) {
            return false;
        }
        if (profile.tutorialMask == 0 && profile.stat("extracts") > 0) {
            profile.tutorialDone = true; // a veteran: nothing to teach
            plugin.profiles().markDirty(player.getUniqueId());
            return false;
        }
        return true;
    }

    /** Marks a step done, whatever order it comes in, then pays out or points out the next one. */
    public void done(Player player, Step step) {
        Profile profile = plugin.profiles().get(player);
        if (!active(player, profile) || (profile.tutorialMask & step.bit()) != 0) {
            return;
        }
        profile.tutorialMask |= step.bit();
        player.playSound(player.getLocation(), "craftov:ui.queue", SoundCategory.MASTER, 0.7f, 1.3f);
        if ((profile.tutorialMask & ALL) == ALL) {
            profile.tutorialDone = true;
            long bonus = plugin.getConfig().getLong("tutorial.reward", 20_000);
            profile.money += bonus;
            Text.send(player, "<ok><b>新手指引完成！</b> <accent>奖励 ◈" + Text.money(bonus) + " <muted>· 之后可以试试每日任务和藏身处。");
        } else if (step != Step.EXTRACT) {
            // After an extract the lobby, a moment later, points out what is left (LobbyService#enterLobby).
            show(player, profile);
        }
        plugin.profiles().markDirty(player.getUniqueId());
    }

    public void show(Player player, Profile profile) {
        Step step = next(player, profile);
        if (step != null) {
            Text.send(player, line(step, true));
        }
    }

    /**
     * The step to point out: the first one still open, except that inside a raid it is the extract
     * while that is open, the one thing that can be done there.
     */
    private @Nullable Step next(Player player, Profile profile) {
        if (!active(player, profile)) {
            return null;
        }
        if (plugin.raids().raidOf(player) != null && (profile.tutorialMask & Step.EXTRACT.bit()) == 0) {
            return Step.EXTRACT;
        }
        for (Step step : Step.values()) {
            if ((profile.tutorialMask & step.bit()) == 0) {
                return step;
            }
        }
        return null;
    }

    /** The hint for a step; in chat the FOV Effects step carries a button that confirms it. */
    private static String line(Step step, boolean chat) {
        return "<accent>新手指引 " + (step.ordinal() + 1) + "/" + Step.values().length + "：<text>" + step.hint
                + (chat && step == Step.FOV
                        ? " <click:run_command:'/cv fov ok'><hover:show_text:'<muted>确认 FOV 特效是 100%'><ok>[已是 100%]</hover></click>" : "");
    }

    /**
     * Repeats the hint on the action bar now and then, outside raids only. In a raid it would cover
     * the short messages that matter there (a kill, an injury, a med used), and the deploy already
     * pointed out the extract in chat; someone dead and watching a squadmate is still in the raid's
     * world. The matchmaking queue keeps the action bar for its countdown.
     */
    private void remind() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.raids().raidOf(player) != null || plugin.raids().raidIn(player.getWorld()) != null
                    || plugin.match().queued(player.getUniqueId()) != null) {
                continue;
            }
            Step step = next(player, plugin.profiles().get(player));
            if (step != null) {
                Notify.info(player, line(step, false));
            }
        }
    }
}
