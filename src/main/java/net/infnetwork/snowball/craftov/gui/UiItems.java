package net.infnetwork.snowball.craftov.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** Interface-only stacks: menu buttons, locked carry slots, the lobby/raid terminal. */
@SuppressWarnings("UnstableApiUsage")
public final class UiItems {

    public static final String LOCKED = "locked";
    public static final String LOBBY_MENU = "lobby_menu";
    public static final String RAID_TERMINAL = "raid_terminal";

    private UiItems() {
    }

    public static ItemStack button(String model, String name, List<String> lore) {
        return button(model, name, lore, "button");
    }

    public static ItemStack button(String model, String name, List<String> lore, String tag) {
        ItemStack stack = ItemStack.of(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(model));
        stack.setData(DataComponentTypes.ITEM_NAME, Text.mm(name));
        stack.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        // Interface buttons get the plain dark tooltip; item tooltips are framed by rarity.
        stack.setData(DataComponentTypes.TOOLTIP_STYLE, Key.key("craftov", "ui"));
        if (!lore.isEmpty()) {
            stack.setData(DataComponentTypes.LORE, ItemLore.lore(Text.mm(lore)));
        }
        stack.editPersistentDataContainer(pdc -> pdc.set(Keys.UI, PersistentDataType.STRING, tag));
        return stack;
    }

    /** Empty filler for menu backgrounds: no tooltip at all. */
    public static ItemStack filler() {
        ItemStack stack = ItemStack.of(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_MODEL, Key.key("craftov:ui/blank"));
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        stack.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        stack.editPersistentDataContainer(pdc -> pdc.set(Keys.UI, PersistentDataType.STRING, "filler"));
        return stack;
    }

    public static ItemStack locked(String reason) {
        ItemStack stack = button("craftov:ui/locked", "<dim>未解锁格位", List.of("<muted>" + reason), LOCKED);
        return stack;
    }

    /** The carry zones a locked cell can belong to, each with its own striped tile (tools/art/ui.py, TILES). */
    public enum Zone {
        RIG("locked_rig", "胸挂", "装备更大的胸挂解锁"),
        PACK("locked_pack", "背包", "装备更大的背包解锁"),
        SECURE("locked_secure", "安全箱", "在商人处升级安全箱解锁");

        final String model;
        final String label;
        final String unlock;

        Zone(String model, String label, String unlock) {
            this.model = model;
            this.label = label;
            this.unlock = unlock;
        }
    }

    /**
     * A cell the zone does not open: diagonal stripes in the zone's colour over the whole slot. Only the
     * {@code first} locked cell of a zone has a tooltip ("战术胸挂 4/9 · 装备更大的胸挂解锁"); the
     * others hide theirs, so moving the pointer along a row of them shows one answer, not nine.
     *
     * @param wearing the worn rig or backpack's name, or null for none
     */
    public static ItemStack lockedCell(Zone zone, boolean first, @org.jspecify.annotations.Nullable String wearing, int open, int max) {
        String title = (wearing == null ? "未装备" + zone.label : Text.escape(wearing)) + " <muted>" + open + "/" + max;
        ItemStack stack = button("craftov:ui/" + zone.model, "<text>" + title,
                List.of("<muted>" + zone.unlock, "<dim>斜纹格暂不可用"), LOCKED);
        if (!first) {
            stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        }
        return stack;
    }

    public static ItemStack lobbyMenu() {
        return button("craftov:ui/terminal", "<accent>战术终端</accent> <muted>(右键)",
                List.of("<muted>开始行动、仓库、商人、交易行、干员……", "<muted>也可以输入 <white>/cv</white> 打开"), LOBBY_MENU);
    }

    public static ItemStack raidTerminal() {
        return button("craftov:ui/terminal", "<accent>战术终端</accent> <muted>(右键)",
                List.of("<muted>安全箱 · 撤离点 · 小队 · 技能",
                        "<muted>潜行+F 释放干员技能"), RAID_TERMINAL);
    }
}
