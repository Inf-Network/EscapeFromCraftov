package net.infnetwork.snowball.craftov.lobby;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The first-join briefing: three short pages in a vanilla dialog window (Paper's dialog API; every
 * 1.21.6+ client shows it without a mod): the goal of a raid, the rules that cost or keep gear, and
 * the controls, which name the keys the player has bound (keybind components) instead of the
 * defaults. Every page can start the guide (also what Esc does, so a reflexive Esc never turns the
 * guide off) or skip it; skipping only turns off the guide's pointers, the steps still count and
 * the bonus for all five is still paid. Either answer means the briefing no longer opens by itself; the terminal's
 * guide button opens it again.
 */
final class Briefing {

    private static final String[] TITLES = {"目标", "规则", "操作"};
    private static final ClickCallback.Options ONCE_IN_A_WHILE = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(30)).build();

    private Briefing() {
    }

    static void open(CraftovPlugin plugin, Player player, int page) {
        player.showDialog(page(plugin, Math.max(0, Math.min(TITLES.length - 1, page))));
    }

    private static Dialog page(CraftovPlugin plugin, int page) {
        List<DialogBody> body = new ArrayList<>();
        for (String line : lines(plugin, page)) {
            body.add(DialogBody.plainMessage(Text.mm(line), 360));
        }
        List<ActionButton> buttons = new ArrayList<>();
        if (page > 0) {
            buttons.add(button("<muted>‹ " + TITLES[page - 1], "上一页", 90, p -> open(plugin, p, page - 1)));
        }
        if (page < TITLES.length - 1) {
            buttons.add(button("<accent>下一页：" + TITLES[page + 1] + " ›", "下一页", 130, p -> open(plugin, p, page + 1)));
        }
        buttons.add(button("<muted>跳过指引", "不显示目标栏、指引箭头和提醒。步骤照样记录，五步做完照样发新手奖励；"
                + "之后可以在战术终端的「新手指引」里重新开启。", 90, p -> answer(plugin, p, true)));
        ActionButton start = button("<ok><b>开始指引", "左上角显示当前目标，罗盘和琥珀色箭头指路。", 150,
                p -> answer(plugin, p, false));
        DialogBase base = DialogBase.builder(Text.mm("<accent>新手简报 <muted>" + (page + 1) + "/" + TITLES.length
                        + " · <text>" + TITLES[page]))
                .externalTitle(Text.mm("新手简报"))
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .body(body)
                .build();
        return Dialog.create(b -> b.empty().base(base).type(DialogType.multiAction(buttons).columns(buttons.size())
                .exitAction(start).build()));
    }

    private static List<String> lines(CraftovPlugin plugin, int page) {
        return switch (page) {
            // Each paragraph is broken by hand: the client wraps at the window's width and left one or
            // two characters on a line of their own.
            case 0 -> List.of(
                    "<text>这是一款<accent>搜打撤</accent>射击：带着装备进入地图，搜刮物资，\n对抗敌兵和其他小队，然后<accent>活着撤离</accent>。",
                    "<text>只有撤离成功，带出来的东西才真正归你：\n存进仓库、卖给商人、挂上交易行都行。",
                    "<muted>每局都有时间限制。撤离点在罗盘上显示为绿色菱形，\n有的要等开放、付费、拉闸，或者不许带背包。",
                    "<muted>新手指引共五步，顺序随意，最后一步是活着撤离；\n五步做完奖励 <accent>◈"
                            + Text.money(plugin.getConfig().getLong("tutorial.reward", 20_000)) + "<muted>。");
            case 1 -> List.of(
                    "<text>大厅里身上穿戴的就是下一局的<accent>出发装备</accent>，\n先在仓库和商人那里配好再出发。",
                    "<text>阵亡会丢掉身上的一切，掉在遗体里谁都能搜走；\n只有<accent>安全箱</accent>里的东西和近战刀能保住。",
                    "<muted>难度越高，物资越好、敌兵越强，入场有战备值门槛和入场费。\n出发前可以给装备投保。",
                    "<muted>没钱也别慌：总资产很少时，战术终端里可以领一套免费补给。");
            default -> List.of(
                    "<white><key:key.attack></white> <muted>开火（按住连发）   <white><key:key.use></white> <muted>按住瞄准",
                    "<white><key:key.drop></white> <muted>换弹   <white><key:key.sneak></white>+<white><key:key.drop></white> <muted>切换弹种",
                    "<white><key:key.swapOffhand></white> <muted>射击模式（拿刀时检视）   <white><key:key.sneak></white>+<white><key:key.swapOffhand></white> <muted>干员技能",
                    "<white><key:key.sneak></white> <muted>静步，敌兵听不到   <white><key:key.pickItem></white> <muted>战术手电",
                    "<white><key:key.use></white> <muted>物资箱和遗体：逐格搜索   对准倒地队友按住 <white><key:key.use></white><muted>：救援",
                    "<muted>医疗品拿在手上按住 <white><key:key.use></white> <muted>使用   <white><key:key.inventory></white> <muted>背包：第 2 行胸挂，第 3、4 行背包",
                    "<white><key:key.hotbar.9></white> <muted>第 9 格战术终端：大厅里是菜单，局内看伤势、安全箱和撤离点");
        };
    }

    private static ActionButton button(String label, String tooltip, int width, Consumer<Player> action) {
        return ActionButton.builder(Text.mm(label)).tooltip(Text.mm("<muted>" + tooltip)).width(width)
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player player) {
                        // Paper may answer a click off the main thread; everything here touches the game.
                        if (Bukkit.isPrimaryThread()) {
                            action.accept(player);
                        } else {
                            Bukkit.getScheduler().runTask(CraftovPlugin.get(), () -> action.accept(player));
                        }
                    }
                }, ONCE_IN_A_WHILE)).build();
    }

    private static void answer(CraftovPlugin plugin, Player player, boolean skip) {
        player.closeDialog();
        if (!player.isOnline()) {
            return;
        }
        plugin.tutorial().mute(player, skip);
        if (skip) {
            Text.send(player, "<muted>已跳过新手指引。步骤照样记录，五步做完照样发奖励；想重新开启，打开战术终端点「新手指引」。");
        } else {
            plugin.tutorial().show(player, plugin.profiles().get(player));
        }
    }
}
