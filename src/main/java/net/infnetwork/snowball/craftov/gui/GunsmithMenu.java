package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.util.Text;
import net.infnetwork.snowball.craftov.weapon.GunSpec;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Gunsmith bench: pick one of the guns you carry, then mount an optic you carry or take the current
 * one off. Works in the lobby, on the range (loaners take only loaner optics) and in a raid.
 */
final class GunsmithMenu extends Menu {

    private final CraftovPlugin plugin;
    private int selected = -1;

    GunsmithMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<#F2E6C8>改枪台"));
        this.plugin = plugin;
    }

    private List<Integer> carriedSlots(boolean guns) {
        List<Integer> out = new ArrayList<>();
        PlayerInventory inv = viewer.getInventory();
        for (int slot : plugin.carry().usableSlots(viewer)) {
            ItemDef def = plugin.items().defOf(inv.getItem(slot));
            if (def != null && (guns ? def.isGun() : def.optic() != null)) {
                out.add(slot);
            }
        }
        return out;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 9; i++) {
            set(9 + i, UiItems.filler());
            set(36 + i, UiItems.filler());
        }
        PlayerInventory inv = viewer.getInventory();
        List<Integer> guns = carriedSlots(true);
        if (guns.isEmpty()) {
            set(22, UiItems.button("craftov:ui/icon_help", "<#9AA0A6>身上没有枪", List.of("<#9AA0A6>把枪放进物品栏再来")));
            return;
        }
        if (!guns.contains(selected)) {
            selected = guns.contains(inv.getHeldItemSlot()) ? inv.getHeldItemSlot() : guns.get(0);
        }
        for (int i = 0; i < guns.size() && i < 9; i++) {
            int slot = guns.get(i);
            ItemStack icon = inv.getItem(slot).clone();
            if (slot == selected) {
                icon.setData(io.papermc.paper.datacomponent.DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            button(i, icon, e -> {
                selected = slot;
                redraw();
            });
        }
        ItemStack gun = inv.getItem(selected);
        ItemDef gunDef = plugin.items().defOf(gun);
        GunSpec spec = gunDef.gun();
        set(22, gun.clone());
        ItemDef mounted = plugin.registry().get(ItemFactory.opticId(gun));
        if ("none".equals(spec.mount())) {
            set(31, UiItems.button("craftov:ui/locked", "<#9AA0A6>这把枪的瞄准镜是固定的", List.of("<#9AA0A6>自带 "
                    + (plugin.registry().get(spec.scope()) == null ? "机械瞄具" : plugin.registry().get(spec.scope()).name()))));
        } else if (mounted != null) {
            ItemStack opticIcon = plugin.items().create(mounted, 1);
            button(31, opticIcon, e -> remove());
            set(30, UiItems.button("craftov:ui/icon_cancel_small", "<#FF8A80>点击右侧瞄准镜拆下", List.of()));
        } else {
            set(31, UiItems.button("craftov:ui/blank", "<#9AA0A6>机械瞄具", List.of("<#9AA0A6>从下方选择一个瞄准镜安装")));
        }
        int col = 0;
        for (int slot : carriedSlots(false)) {
            ItemStack stack = inv.getItem(slot);
            ItemDef def = plugin.items().defOf(stack);
            boolean fits = !"none".equals(spec.mount()) && spec.accepts(def.optic());
            boolean sameKind = ItemFactory.isRange(stack) == ItemFactory.isRange(gun);
            ItemStack icon = stack.clone();
            icon.setAmount(1);
            List<String> note = new ArrayList<>();
            if (!fits) {
                note.add("<#FF8A80>装不上这把枪");
            } else if (!sameKind) {
                note.add(ItemFactory.isRange(gun) ? "<#FF8A80>靶场武器只能装靶场的瞄准镜" : "<#FF8A80>靶场瞄准镜只能装在靶场武器上");
            } else {
                note.add("<#5BD16B>点击安装");
            }
            button(45 + col, withNote(icon, note), e -> install(slot));
            if (++col >= 9) {
                break;
            }
        }
        if (col == 0) {
            set(49, UiItems.button("craftov:ui/icon_help", "<#9AA0A6>身上没有瞄准镜", List.of(
                    "<#9AA0A6>瞄准镜可以在商人「配件」页购买，", "<#9AA0A6>也会出现在武器箱和保险箱里")));
        }
    }

    private static ItemStack withNote(ItemStack stack, List<String> note) {
        ItemStack copy = stack.clone();
        io.papermc.paper.datacomponent.item.ItemLore lore = copy.getData(io.papermc.paper.datacomponent.DataComponentTypes.LORE);
        List<net.kyori.adventure.text.Component> lines = new ArrayList<>(lore == null ? List.of() : lore.lines());
        for (String line : note) {
            lines.add(Text.mm(line));
        }
        copy.setData(io.papermc.paper.datacomponent.DataComponentTypes.LORE, io.papermc.paper.datacomponent.item.ItemLore.lore(lines));
        return copy;
    }

    private void install(int opticSlot) {
        PlayerInventory inv = viewer.getInventory();
        ItemStack gun = inv.getItem(selected);
        ItemStack optic = inv.getItem(opticSlot);
        ItemDef gunDef = plugin.items().defOf(gun);
        ItemDef opticDef = plugin.items().defOf(optic);
        if (gunDef == null || !gunDef.isGun() || opticDef == null || opticDef.optic() == null) {
            redraw();
            return;
        }
        if ("none".equals(gunDef.gun().mount()) || !gunDef.gun().accepts(opticDef.optic())) {
            viewer.sendActionBar(Text.mm("<#FF8A80>" + Text.escape(opticDef.name()) + " 装不上 " + Text.escape(gunDef.name())));
            return;
        }
        if (ItemFactory.isRange(gun) != ItemFactory.isRange(optic)) {
            viewer.sendActionBar(Text.mm("<#FF8A80>靶场武器和自己的配件不能混装"));
            return;
        }
        plugin.weapons().reset(viewer);
        String old = ItemFactory.opticId(gun);
        optic.setAmount(optic.getAmount() - 1);
        inv.setItem(opticSlot, optic.getAmount() > 0 ? optic : null);
        inv.setItem(selected, plugin.items().withOptic(gun, opticDef.id()));
        giveBack(old, ItemFactory.isRange(gun));
        viewer.playSound(viewer.getLocation(), "craftov:gun.reload_done", 0.8f, 1.2f);
        viewer.sendActionBar(Text.mm("<#5BD16B>已安装 " + opticDef.coloredName()));
        redraw();
    }

    private void remove() {
        PlayerInventory inv = viewer.getInventory();
        ItemStack gun = inv.getItem(selected);
        String old = ItemFactory.opticId(gun);
        if (old == null) {
            return;
        }
        plugin.weapons().reset(viewer);
        inv.setItem(selected, plugin.items().withOptic(gun, null));
        giveBack(old, ItemFactory.isRange(gun));
        viewer.playSound(viewer.getLocation(), "craftov:gun.mode", 0.8f, 1f);
        redraw();
    }

    private void giveBack(String opticId, boolean range) {
        ItemDef def = plugin.registry().get(opticId);
        if (def == null) {
            return;
        }
        ItemStack stack = plugin.items().create(def, 1);
        plugin.carry().give(viewer, range ? plugin.items().markRange(stack) : stack, true);
    }
}
