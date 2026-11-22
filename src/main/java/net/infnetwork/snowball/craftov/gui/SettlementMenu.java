package net.infnetwork.snowball.craftov.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The raid result, shown in the lobby right after an extraction or after a death (once the kill
 * replay and spectating are over), instead of a report in chat. The outcome, a row of figures (time,
 * kills, experience, rank points, value brought in and out, net result, new collectibles), the ten
 * most valuable items taken out or lost, and back to the lobby, the stash or straight into another
 * raid. Chat keeps one summary line.
 */
public final class SettlementMenu extends Menu {

    /** Everything the screen shows, gathered when the raid ends for this player. */
    public record Settlement(boolean extracted, String outcome, String map, String tier, int seconds, int kills, int aiKills,
                             long xp, int rankDelta, long broughtIn, long broughtOut, long net, List<ItemStack> items,
                             List<String> collectibles, @Nullable String killer) {

        /** The one chat line kept as a record. */
        public String summary() {
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

    private final CraftovPlugin plugin;
    private final Settlement s;

    public SettlementMenu(CraftovPlugin plugin, Player viewer, Settlement settlement) {
        super(viewer, 6, settlement.extracted() ? "settlement_ok" : "settlement_fail",
                // The two figures that matter most stay readable without hovering: net result and time.
                Text.mm("行动结算 <muted>· " + (settlement.net() >= 0 ? "<ok>+◈" + Text.money(settlement.net())
                        : "<danger>-◈" + Text.money(-settlement.net())) + " <muted>· " + Text.duration(settlement.seconds())));
        this.plugin = plugin;
        this.s = settlement;
    }

    @Override
    protected void draw() {
        List<String> report = new ArrayList<>(List.of("<muted>" + Text.escape(s.outcome())));
        if (s.killer() != null) {
            report.add("<muted>击杀者 <danger>" + Text.escape(s.killer()));
        }
        report.add("<muted>地图 <white>" + Text.escape(s.map()) + " <muted>· " + Text.escape(s.tier()));
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
        set(17, UiItems.button("craftov:ui/icon_collection", "<text>新收藏品 <white>" + s.collectibles().size(),
                found.isEmpty() ? List.of("<muted>这次没有新的收藏品") : found));
        for (int i = 0; i < ITEM_SLOTS.length && i < s.items().size(); i++) {
            set(ITEM_SLOTS[i], s.items().get(i));
        }
        button(45, UiItems.button("craftov:ui/arrow_left", "<text><b>返回大厅", List.of("<muted>关闭结算")), e -> viewer.closeInventory());
        button(49, UiItems.button("craftov:ui/icon_stash", "<text><b>打开仓库", List.of("<muted>整理带回来的物资")),
                e -> plugin.menus().openStash(viewer));
        bigButton(AGAIN, "<accent><b>再来一局", List.of("<muted>打开部署界面"), e -> plugin.menus().openDeploy(viewer));
    }

    private void stat(int slot, String icon, String label, String value) {
        stat(slot, icon, label, value, 1);
    }

    /** A figure: its icon, the label and value as its name, and for counts the count on the stack. */
    private void stat(int slot, String icon, String label, String value, int count) {
        ItemStack stack = UiItems.button("craftov:ui/" + icon, "<muted>" + label + " " + value, List.of());
        stack.setData(DataComponentTypes.MAX_STACK_SIZE, 99);
        stack.setAmount(Math.max(1, Math.min(99, count)));
        set(slot, stack);
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
