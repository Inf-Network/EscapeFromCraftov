package net.infnetwork.snowball.craftov.lobby;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * First steps for a new player: open the terminal, try a gun on the range, fit an attachment at the
 * gunsmith, deploy, and extract. Each step is pointed out until done; finishing pays a starter bonus.
 * Steps only count in order, and the guide stays quiet once a player has extracted before.
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
    }

    private final CraftovPlugin plugin;

    public TutorialService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::remind, 20L * 30, 20L * 45);
    }

    private boolean active(Profile profile) {
        return !profile.tutorialDone && plugin.getConfig().getBoolean("tutorial.enabled", true);
    }

    /** Marks a step done if it is the current one, then shows the next. */
    public void done(Player player, Step step) {
        Profile profile = plugin.profiles().get(player);
        if (!active(profile)) {
            return;
        }
        if (profile.stat("extracts") > 0) {
            profile.tutorialDone = true; // a veteran: nothing to teach
            return;
        }
        if (profile.tutorialStep != step.ordinal()) {
            return;
        }
        profile.tutorialStep++;
        player.playSound(player.getLocation(), "craftov:ui.queue", SoundCategory.MASTER, 0.7f, 1.3f);
        if (profile.tutorialStep >= Step.values().length) {
            profile.tutorialDone = true;
            long bonus = plugin.getConfig().getLong("tutorial.reward", 20_000);
            profile.money += bonus;
            Text.send(player, "<#5BD16B><b>新手指引完成！</b> <#F2C94C>奖励 ◈" + Text.money(bonus) + " <#9AA0A6>· 之后可以试试每日任务和藏身处。");
        } else {
            show(player, profile);
        }
        plugin.profiles().markDirty(player.getUniqueId());
    }

    public void show(Player player, Profile profile) {
        if (!active(profile) || profile.tutorialStep >= Step.values().length) {
            return;
        }
        Step step = Step.values()[profile.tutorialStep];
        Text.send(player, "<#F2C94C>新手指引 " + (step.ordinal() + 1) + "/" + Step.values().length + "：<#C8CCD2>" + step.hint);
    }

    private void remind() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Profile profile = plugin.profiles().get(player);
            if (active(profile) && profile.tutorialStep < Step.values().length && profile.stat("extracts") == 0) {
                Step step = Step.values()[profile.tutorialStep];
                player.sendActionBar(Text.mm("<#F2C94C>新手指引 " + (step.ordinal() + 1) + "/" + Step.values().length + "：<#C8CCD2>" + step.hint));
            }
        }
    }
}
