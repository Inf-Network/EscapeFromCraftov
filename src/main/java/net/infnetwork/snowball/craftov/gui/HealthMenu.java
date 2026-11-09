package net.infnetwork.snowball.craftov.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.BodyPart;
import net.infnetwork.snowball.craftov.combat.Injury;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wounds at a glance: a front-view body made of tinted part icons, what each wound does, and the
 * medical items on hand (click one to put it in your hand, then hold right-click to use it).
 */
@SuppressWarnings("UnstableApiUsage")
final class HealthMenu extends Menu {

    private static final int[] STATE_COLORS = {0x7E848C, 0xFF8A80, 0xFF2D2D, 0xFFB74D, 0xFF6B3D};

    private final CraftovPlugin plugin;
    private int lastHash;

    HealthMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<#F2E6C8>伤势"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        Map<BodyPart, Set<Injury>> wounds = plugin.health().wounds(viewer);
        part(4, BodyPart.HEAD, "head", wounds);
        part(12, BodyPart.RIGHT_ARM, "arm", wounds);
        part(13, BodyPart.CHEST, "chest", wounds);
        part(14, BodyPart.LEFT_ARM, "arm", wounds);
        part(21, BodyPart.RIGHT_ARM, "arm", wounds);
        part(22, BodyPart.STOMACH, "stomach", wounds);
        part(23, BodyPart.LEFT_ARM, "arm", wounds);
        part(30, BodyPart.RIGHT_LEG, "leg", wounds);
        part(32, BodyPart.LEFT_LEG, "leg", wounds);
        part(39, BodyPart.RIGHT_LEG, "leg", wounds);
        part(41, BodyPart.LEFT_LEG, "leg", wounds);

        int hp = (int) Math.ceil(viewer.getHealth());
        List<String> status = new ArrayList<>();
        status.add("<#8A8F98>生命 <white>" + hp + " / 100");
        status.add("<#8A8F98>体力 <white>" + Math.round(plugin.stamina().stamina(viewer)) + (plugin.stamina().exhausted(viewer) ? " <#FF8A80>力竭" : ""));
        status.add("<#8A8F98>负重 <white>" + String.format("%.1f", plugin.stamina().weight(viewer)) + "kg"
                + (plugin.stamina().overweightPenalty(viewer) > 0 ? " <#FFB74D>超重，移动变慢" : ""));
        if (plugin.health().painkilled(viewer)) {
            status.add("<#7FE0D0>止痛中 " + plugin.health().painkillerSeconds(viewer) + "s <#8A8F98>（骨折暂不影响行动）");
        }
        if (plugin.health().adrenaline(viewer)) {
            status.add("<#FF9F43>肾上腺素生效中");
        }
        set(7, UiItems.button("craftov:ui/icon_profile", "<#F2E6C8><b>身体状况", status));
        set(16, UiItems.button("craftov:ui/icon_help", "<#C8CCD2><b>伤势说明", List.of(
                "<#FF8A80>出血 <#9AA0A6>持续掉血，用绷带 / 止血敷料 / 医疗箱",
                "<#FF2D2D>大出血 <#9AA0A6>掉血很快，用止血敷料或军用医疗箱",
                "<#FFB74D>腿部骨折 <#9AA0A6>移动变慢、无法冲刺、跳不高",
                "<#FFB74D>手臂骨折 <#9AA0A6>射击扩散和后坐力变大、换弹变慢",
                "<#9AA0A6>夹板治疗一处骨折，手术包处理全身伤势",
                "<#9AA0A6>止痛药暂时屏蔽骨折影响，但不会治好它")));

        List<Integer> meds = new ArrayList<>();
        PlayerInventory inv = viewer.getInventory();
        for (int slot : plugin.carry().usableSlots(viewer)) {
            ItemDef def = plugin.items().defOf(inv.getItem(slot));
            if (def != null && def.med() != null) {
                meds.add(slot);
            }
        }
        for (int i = 0; i < 9; i++) {
            set(45 + i, UiItems.filler());
        }
        for (int i = 0; i < meds.size() && i < 9; i++) {
            int slot = meds.get(i);
            ItemStack stack = inv.getItem(slot).clone();
            button(45 + i, stack, e -> takeInHand(slot));
        }
        if (meds.isEmpty()) {
            set(49, UiItems.button("craftov:ui/icon_cancel_small", "<#9AA0A6>身上没有医疗物品", List.of()));
        }
        lastHash = wounds.hashCode() * 31 + hp;
    }

    private void part(int slot, BodyPart part, String shape, Map<BodyPart, Set<Injury>> wounds) {
        int state = plugin.health().partState(viewer, part);
        Set<Injury> set = wounds.getOrDefault(part, Set.of());
        List<String> lore = new ArrayList<>();
        if (set.isEmpty()) {
            lore.add("<#5BD16B>正常");
        } else {
            for (Injury injury : set) {
                lore.add("<" + injury.hex() + ">● " + injury.displayName() + " <#9AA0A6>" + switch (injury) {
                    case LIGHT_BLEED -> "→ 绷带 / 止血敷料 / 急救包";
                    case HEAVY_BLEED -> "→ 止血敷料 / 军用医疗箱 / 手术包";
                    case FRACTURE -> "→ 夹板 / 手术包";
                });
            }
        }
        ItemStack icon = UiItems.button("craftov:ui/body_" + shape, (set.isEmpty() ? "<#C8CCD2>" : "<#FF8A80>") + "<b>" + part.displayName(), lore);
        icon.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(Color.fromRGB(STATE_COLORS[state])));
        icon.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().addHiddenComponents(DataComponentTypes.DYED_COLOR).build());
        set(slot, icon);
    }

    /** Swaps a medical item into the held hotbar slot so it can be used straight away. */
    private void takeInHand(int from) {
        PlayerInventory inv = viewer.getInventory();
        int held = inv.getHeldItemSlot();
        if (held == CarryService.TERMINAL_SLOT) {
            held = 0;
            inv.setHeldItemSlot(0);
        }
        if (from != held) {
            plugin.weapons().flush(viewer);
            ItemStack med = inv.getItem(from);
            ItemStack current = inv.getItem(held);
            inv.setItem(held, med);
            inv.setItem(from, current);
        }
        viewer.closeInventory();
        viewer.sendActionBar(Text.mm("<#7FE0D0>按住右键使用"));
    }

    @Override
    public boolean ticking() {
        return true;
    }

    @Override
    public void tick() {
        int hash = plugin.health().wounds(viewer).hashCode() * 31 + (int) Math.ceil(viewer.getHealth());
        if (hash != lastHash) {
            redraw();
        }
    }
}
