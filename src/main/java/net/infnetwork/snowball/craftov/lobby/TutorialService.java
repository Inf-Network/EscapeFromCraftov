package net.infnetwork.snowball.craftov.lobby;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * First steps for a new player: open the terminal, try a gun on the range, fit an attachment at the
 * gunsmith, deploy, and extract. Steps count in any order; the first one still open is pointed out
 * until done, and finishing all five pays a starter bonus. A player who had extracted before the
 * guide existed never sees it.
 */
public final class TutorialService {

    public enum Step {
        TERMINAL("右键快捷栏第 9 格的<white>战术终端</white>，打开大厅菜单"),
        RANGE("到大厅东侧的<white>靶场入口</white>右键进入个人靶场，试一把枪"),
        GUNSMITH("打开<white>改枪台</white>（菜单或 /cv mod），给枪装上任意一个配件"),
        DEPLOY("在菜单里点<white>开始行动</white>，选择地图出发"),
        EXTRACT("在局内找到<white>撤离点</white>（战术终端可查看），活着撤离");

        public final String hint;

        Step(String hint) {
            this.hint = hint;
        }

        int bit() {
            return 1 << ordinal();
        }
    }

    private static final int ALL = (1 << Step.values().length) - 1;

    private final CraftovPlugin plugin;

    public TutorialService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::remind, 20L * 30, 20L * 45);
    }

    /**
     * Turns the old step counter of a saved profile into the set of finished steps: a counter at
     * {@code n} had the first {@code n} steps done. The old guide also closed itself, without the
     * reward, for a new player who extracted before reaching the extract step (it took anyone with
     * an extract for a veteran on their next step); such a guide (closed, counter short of the end)
     * is opened again with the deploy and the extract that closed it counted. Called on load.
     */
    public static void migrate(Profile profile) {
        if (profile.tutorialStep <= 0) {
            return;
        }
        int steps = Math.min(profile.tutorialStep, Step.values().length);
        profile.tutorialMask |= (1 << steps) - 1;
        if (profile.tutorialDone && steps < Step.values().length && profile.stat("extracts") > 0) {
            profile.tutorialDone = false;
            profile.tutorialMask |= Step.DEPLOY.bit() | Step.EXTRACT.bit();
        }
        profile.tutorialStep = 0;
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
            Text.send(player, "<#5BD16B><b>新手指引完成！</b> <#F2C94C>奖励 ◈" + Text.money(bonus) + " <#9AA0A6>· 之后可以试试每日任务和藏身处。");
        } else if (step != Step.EXTRACT) {
            // After an extract the lobby, a moment later, points out what is left (LobbyService#enterLobby).
            show(player, profile);
        }
        plugin.profiles().markDirty(player.getUniqueId());
    }

    public void show(Player player, Profile profile) {
        Step step = next(player, profile);
        if (step != null) {
            Text.send(player, line(step));
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

    private static String line(Step step) {
        return "<#F2C94C>新手指引 " + (step.ordinal() + 1) + "/" + Step.values().length + "：<#C8CCD2>" + step.hint;
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
                player.sendActionBar(Text.mm(line(step)));
            }
        }
    }
}
