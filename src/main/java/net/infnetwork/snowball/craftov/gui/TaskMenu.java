package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.task.TaskService;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Daily contracts: progress, rewards and right-click to swap an unfinished one. */
final class TaskMenu extends Menu {

    private static final int[] SLOTS = {11, 13, 15};

    private final CraftovPlugin plugin;
    private final boolean fromTerminal;

    TaskMenu(CraftovPlugin plugin, Player viewer, boolean fromTerminal) {
        super(viewer, 3, Text.mm("<#F2E6C8>每日任务"));
        this.plugin = plugin;
        this.fromTerminal = fromTerminal;
    }

    @Override
    protected void draw() {
        Profile profile = plugin.profiles().get(viewer);
        List<Profile.TaskState> tasks = plugin.tasks().of(profile);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime reset = now.minusHours(4).toLocalDate().plusDays(1).atTime(4, 0);
        Duration left = Duration.between(now, reset);
        long done = tasks.stream().filter(t -> t.done).count();
        set(4, UiItems.button("craftov:ui/icon_help", "<#F2E6C8><b>每日任务 <#9AA0A6>" + done + "/" + tasks.size(), List.of(
                "<#9AA0A6>每天 04:00 刷新，完成即发放奖励",
                "<#9AA0A6>全部完成额外奖励 <#F2C94C>◈" + Text.money(plugin.getConfig().getLong("tasks.all-done-bonus", 20000)),
                "<#8A8F98>距离刷新 " + left.toHours() + " 小时 " + left.toMinutesPart() + " 分")));
        for (int i = 0; i < tasks.size() && i < SLOTS.length; i++) {
            Profile.TaskState task = tasks.get(i);
            List<String> lore = new ArrayList<>();
            lore.add((task.done ? "<#5BD16B>已完成" : "<#F2C94C>进度 " + TaskService.progressText(task)));
            lore.add("<#9AA0A6>奖励 <#F2C94C>◈" + Text.money(task.reward) + " <#9AA0A6>· " + task.xp + " 经验");
            if (!task.done) {
                lore.add("");
                lore.add("<#8A8F98>右键更换任务（◈" + Text.money(plugin.tasks().rerollCost()) + "）");
            }
            String name = (task.done ? "<#5BD16B><b>✔ " : "<#F2E6C8><b>") + Text.escape(TaskService.describe(task));
            int index = i;
            button(SLOTS[i], UiItems.button(task.done ? "craftov:ui/report_ok" : "craftov:ui/icon_start", name, lore), event -> {
                if (event.getClick() == ClickType.RIGHT && plugin.tasks().reroll(viewer, index)) {
                    viewer.playSound(viewer.getLocation(), "minecraft:ui.button.click", SoundCategory.MASTER, 0.6f, 1.2f);
                    redraw();
                }
            });
        }
        button(22, UiItems.button("craftov:ui/arrow_left", "<#9AA0A6>返回", List.of()), event -> {
            if (fromTerminal) {
                plugin.menus().openTerminal(viewer);
            } else {
                plugin.menus().openMain(viewer);
            }
        });
    }
}
