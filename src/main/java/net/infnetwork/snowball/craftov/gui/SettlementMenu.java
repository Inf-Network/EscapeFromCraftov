package net.infnetwork.snowball.craftov.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.raid.TrainingService;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The raid result, shown in the lobby right after an extraction or after a death (once the kill
 * replay and spectating are over), instead of a report in chat. The outcome (with the rounds fired
 * and how many of them standing still; after a death the killing blow's weapon, body part and
 * distance and the hits taken), a row of figures (time,
 * kills, experience, rank points, value brought in and out, net result, new collectibles), the ten
 * most valuable items taken out or lost, and back to the lobby, the stash or straight into another
 * raid. Chat keeps one summary line. A training raid has its own screen: the lessons done, the time
 * and the rounds fired, with no money, experience or items, since nothing in it counts.
 */
public final class SettlementMenu extends Menu {

    /**
     * Everything the screen shows, gathered when the raid ends for this player. {@code lessons} is a
     * training raid's checklist, whether each {@link TrainingService.Lesson} was done (empty otherwise).
     */
    public record Settlement(boolean extracted, String outcome, String map, String tier, int seconds, int kills, int aiKills,
                             long xp, int rankDelta, long broughtIn, long broughtOut, long net, List<ItemStack> items,
                             List<String> collectibles, @Nullable String killer, List<String> wounds, int shots, int stillShots,
                             boolean training, List<Boolean> lessons) {

        /** A training raid's result. */
        public static Settlement training(boolean done, String outcome, String map, int seconds, List<Boolean> lessons,
                                          int shots, int stillShots) {
            return new Settlement(done, outcome, map, "训练", seconds, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), null,
                    List.of(), shots, stillShots, true, lessons);
        }

        /** The one chat line kept as a record. */
        public String summary() {
            if (training) {
                return (extracted ? "<ok>训练完成" : "<danger>训练中断 <muted>· <white>" + Text.escape(outcome))
                        + " <muted>· 用时 " + Text.duration(seconds) + " · 不计战绩，带进去的装备已原样归还";
            }
            return (extracted ? "<ok>撤离成功" : "<danger>行动失败 <muted>· <white>" + Text.escape(outcome))
                    + " <muted>· " + Text.escape(map) + " · " + Text.escape(tier)
                    + " · " + Text.duration(seconds) + " · " + (extracted
                    ? "净收益 " + (net >= 0 ? "<ok>+◈" : "<danger>-◈") + Text.money(Math.abs(net))
                    : "损失 <danger>◈" + Text.money(-net))
                    + " <muted>· 击败干员 <white>" + kills + " <muted>· 经验 <white>+" + xp;
        }
    }

    private static final int[] ITEM_SLOTS = {29, 30, 31, 32, 33, 38, 39, 40, 41, 42};
    private static final int[] AGAIN = {51, 52, 53};
    /** The training screen's plate, one cell per lesson (tools/art/screens.py, training_settlement_screen). */
    private static final int[] LESSON_SLOTS = {28, 29, 30, 31, 32, 33};

    private final CraftovPlugin plugin;
    private final Settlement s;

    public SettlementMenu(CraftovPlugin plugin, Player viewer, Settlement settlement) {
        super(viewer, 6, settlement.training() ? (settlement.extracted() ? "settlement_training_ok" : "settlement_training_fail")
                        : settlement.extracted() ? "settlement_ok" : "settlement_fail",
                // The two figures that matter most stay readable without hovering: net result and time.
                settlement.training() ? Text.mm("训练结算 <muted>· " + (settlement.extracted() ? "<ok>完成" : "<danger>中断")
                        + " <muted>· " + Text.duration(settlement.seconds()))
                        : Text.mm("行动结算 <muted>· " + (settlement.net() >= 0 ? "<ok>+◈" + Text.money(settlement.net())
                        : "<danger>-◈" + Text.money(-settlement.net())) + " <muted>· " + Text.duration(settlement.seconds())));
        this.plugin = plugin;
        this.s = settlement;
    }

    @Override
    protected void draw() {
        if (s.training()) {
            drawTraining();
            return;
        }
        List<String> report = new ArrayList<>(List.of("<muted>" + Text.escape(s.outcome())));
        if (s.killer() != null) {
            report.add("<muted>击杀者 <danger>" + Text.escape(s.killer()));
        }
        // A death's damage record: the killing blow with weapon, body part and distance, then the hits taken.
        report.addAll(s.wounds());
        report.add("<muted>地图 <white>" + Text.escape(s.map()) + " <muted>· " + Text.escape(s.tier()));
        if (s.shots() > 0) {
            // How many rounds went out standing still, where moving no longer widens the spread (#8).
            report.add("<muted>开火 <white>" + s.shots() + " <muted>发 · 急停射击 <white>" + s.stillShots() + " <muted>发（"
                    + Math.round(100.0 * s.stillShots() / s.shots()) + "%）");
            report.add("<dim>停稳再开枪不受移动影响");
        }
        set(8, UiItems.button(s.extracted() ? "craftov:ui/report_ok" : "craftov:ui/report_fail",
                (s.extracted() ? "<ok><b>撤离成功" : "<danger><b>行动失败"), report));
        stat(9, "icon_mode", "用时", "<white>" + Text.duration(s.seconds()));
        stat(10, "icon_deploy", "击败干员", "<white>" + s.kills(), s.kills());
        stat(11, "icon_bounty", "击败敌兵", "<white>" + s.aiKills(), s.aiKills());
        stat(12, "icon_upgrade", "经验", "<white>+" + s.xp());
        stat(13, "icon_rank", "排位分", s.rankDelta() == 0 ? "<muted>±0" : s.rankDelta() > 0 ? "<ok>+" + s.rankDelta() : "<danger>" + s.rankDelta());
        stat(14, "icon_stash", "带入价值", "<white>◈" + Text.money(s.broughtIn()));
        stat(15, "icon_secure", s.extracted() ? "带出价值" : "安全箱带出", "<white>◈" + Text.money(s.broughtOut()));
        stat(16, "icon_value", s.extracted() ? "净收益" : "损失",
                s.net() >= 0 ? "<ok>+◈" + Text.money(s.net()) : "<danger>-◈" + Text.money(-s.net()));
        List<String> found = new ArrayList<>();
        for (String c : s.collectibles()) {
            found.add("<danger>★ <white>" + Text.escape(c));
        }
        ItemStack collection = UiItems.button("craftov:ui/icon_collection", "<text>新收藏品 <white>" + s.collectibles().size(),
                found.isEmpty() ? List.of("<muted>这次没有新的收藏品") : found);
        counted(collection, s.collectibles().size());
        set(17, collection);
        for (int i = 0; i < ITEM_SLOTS.length && i < s.items().size(); i++) {
            set(ITEM_SLOTS[i], s.items().get(i));
        }
        button(45, UiItems.button("craftov:ui/arrow_left", "<text><b>返回大厅", List.of("<muted>关闭结算")), e -> viewer.closeInventory());
        button(49, UiItems.button("craftov:ui/icon_stash", "<text><b>打开仓库", List.of("<muted>整理带回来的物资")),
                e -> plugin.menus().openStash(viewer));
        bigButton(AGAIN, "<accent><b>再来一局", List.of("<muted>打开部署界面"), e -> plugin.menus().openDeploy(viewer));
    }

    /**
     * A training raid's screen (its own background: no items plate, nothing "lost"): the outcome,
     * the time, the rounds fired and what a real raid is like, the lessons on the plate (done ones
     * shine, the rest are locked), and the way on: back to the lobby, or a real raid.
     */
    private void drawTraining() {
        set(8, UiItems.button(s.extracted() ? "craftov:ui/report_ok" : "craftov:ui/report_fail",
                s.extracted() ? "<ok><b>训练完成" : "<danger><b>训练中断", List.of(
                        "<muted>" + Text.escape(s.outcome()),
                        "<muted>训练不计战绩：带进去的装备和安全箱已原样归还，",
                        "<muted>训练里拿到的枪、弹药和药品已收回。")));
        stat(9, "icon_mode", "用时", "<white>" + Text.duration(s.seconds()));
        stat(10, "icon_deploy", "开火", "<white>" + s.shots() + " <muted>发 · 急停射击 <white>" + s.stillShots() + " <muted>发");
        set(11, UiItems.button("craftov:ui/icon_help", "<accent>接下来", List.of(
                "<muted>真正的行动里：身上的装备就是出发装备，",
                "<muted>阵亡会丢掉安全箱以外的一切，撤离才能带回物资。")));
        TrainingService.Lesson[] lessons = TrainingService.Lesson.values();
        for (int i = 0; i < lessons.length && i < LESSON_SLOTS.length; i++) {
            boolean done = i < s.lessons().size() && s.lessons().get(i);
            ItemStack cell = UiItems.button("craftov:ui/" + (done ? lessons[i].icon : "locked"),
                    done ? "<ok>✔ <text>" + lessons[i].title : "<dim>○ " + lessons[i].title,
                    List.of(done ? "<muted>第 " + (i + 1) + " 课 · 已完成" : "<muted>第 " + (i + 1) + " 课 · 没有完成"));
            counted(cell, done ? 1 : 0);
            set(LESSON_SLOTS[i], cell);
        }
        button(45, UiItems.button("craftov:ui/arrow_left", "<text><b>返回大厅", List.of("<muted>关闭结算")), e -> viewer.closeInventory());
        button(49, UiItems.button("craftov:ui/icon_stash", "<text><b>打开仓库", List.of("<muted>配好装备再出发")),
                e -> plugin.menus().openStash(viewer));
        bigButton(AGAIN, "<accent><b>开始行动", List.of("<muted>打开部署界面，选一张地图出发"), e -> plugin.menus().openDeploy(viewer));
    }

    private void stat(int slot, String icon, String label, String value) {
        set(slot, UiItems.button("craftov:ui/" + icon, "<muted>" + label + " " + value, List.of()));
    }

    /** A count: its icon, the label and value as its name, and the count on the stack. */
    private void stat(int slot, String icon, String label, String value, int count) {
        ItemStack stack = UiItems.button("craftov:ui/" + icon, "<muted>" + label + " " + value, List.of());
        counted(stack, count);
        set(slot, stack);
    }

    /**
     * A count on an icon without hovering: the number on the stack from 2 up (the client never draws
     * a 1), and a shine on anything above 0, so one kill and none do not look the same.
     */
    private static void counted(ItemStack stack, int count) {
        stack.setData(DataComponentTypes.MAX_STACK_SIZE, 99);
        stack.setAmount(Math.max(1, Math.min(99, count)));
        if (count > 0) {
            stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
    }

    /** Copies of the most valuable stacks, for display only (they carry no Craftov id once shown here). */
    public static List<ItemStack> top(CraftovPlugin plugin, List<ItemStack> stacks, int limit) {
        List<ItemStack> sorted = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty() && plugin.items().defOf(stack) != null) {
                sorted.add(stack.clone());
            }
        }
        sorted.sort((a, b) -> Long.compare(plugin.items().value(b), plugin.items().value(a)));
        List<ItemStack> out = new ArrayList<>(sorted.subList(0, Math.min(limit, sorted.size())));
        for (ItemStack stack : out) {
            List<Component> lore = new ArrayList<>(stack.getData(DataComponentTypes.LORE) == null ? List.of()
                    : stack.getData(DataComponentTypes.LORE).lines());
            lore.add(Text.mm("<muted>价值 <accent>◈" + Text.money(plugin.items().value(stack))));
            stack.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        }
        return out;
    }
}
