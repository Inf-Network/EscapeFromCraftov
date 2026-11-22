package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loadout presets: save the current carry layout, then rebuild it later by pulling items from the
 * stash and buying whatever is missing from the trader.
 */
final class PresetMenu extends Menu {

    private static final int MAX = 5;
    private final CraftovPlugin plugin;

    PresetMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 3, Text.mm("<text>配装方案"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        Profile p = plugin.profiles().get(viewer);
        for (int i = 0; i < MAX; i++) {
            int index = i;
            int slot = 10 + i * 2 - (i > 2 ? 0 : 0);
            if (i < p.presets.size()) {
                Profile.Preset preset = p.presets.get(i);
                List<String> lore = new ArrayList<>();
                long cost = 0;
                for (Map.Entry<Integer, String> e : preset.slots.entrySet()) {
                    ItemDef def = plugin.registry().get(e.getValue());
                    int amount = preset.amounts.getOrDefault(e.getKey(), 1);
                    String count = amount > 1 ? " <muted>×" + amount : "";
                    if (def == null) {
                        lore.add("<muted>· <muted>未知物品 " + Text.escape(e.getValue()) + count);
                        continue;
                    }
                    lore.add("<muted>· " + def.coloredName() + count);
                    if (sold(def)) {
                        cost += plugin.traders().buyPrice(def) * amount;
                    }
                }
                if (lore.size() > 12) {
                    lore = new ArrayList<>(lore.subList(0, 12));
                    lore.add("<muted>……");
                }
                lore.add("");
                lore.add("<muted>商人有售的部分全部购买约 ◈" + Text.money(cost) + "（优先使用仓库中的物品）");
                lore.add("<ok>左键 一键配装  <danger>潜行右键 删除  <accent>右键 覆盖保存");
                button(slot, UiItems.button("craftov:ui/icon_preset", "<text><b>" + Text.escape(preset.name), lore), e -> {
                    if (e.isShiftClick() && e.isRightClick()) {
                        p.presets.remove(index);
                    } else if (e.isRightClick()) {
                        p.presets.set(index, capture(preset.name));
                        Notify.ok(viewer, "已覆盖配装方案 " + Text.escape(preset.name));
                    } else {
                        apply(preset);
                    }
                    plugin.profiles().markDirty(viewer.getUniqueId());
                    redraw();
                });
            } else {
                button(slot, UiItems.button("craftov:ui/icon_upgrade", "<muted>空方案 " + (i + 1),
                        List.of("<muted>点击把当前身上的配装保存为方案")), e -> {
                    p.presets.add(capture("方案 " + (p.presets.size() + 1)));
                    plugin.profiles().markDirty(viewer.getUniqueId());
                    Notify.ok(viewer, "已保存当前配装。");
                    redraw();
                });
            }
        }
        set(22, UiItems.button("craftov:ui/icon_help", "<white>说明", List.of(
                "<muted>一键配装会把身上的物品先存回仓库，", "<muted>再按方案从仓库取出或向商人购买。",
                "<muted>商人不出售的物品（金色/红色）只能从仓库取。")));
    }

    private Profile.Preset capture(String name) {
        plugin.weapons().flush(viewer);
        Profile.Preset preset = new Profile.Preset(name);
        for (Map.Entry<Integer, ItemRecord> e : plugin.carry().snapshot(viewer).entrySet()) {
            preset.slots.put(e.getKey(), e.getValue().id);
            preset.amounts.put(e.getKey(), e.getValue().amount);
        }
        return preset;
    }

    private void apply(Profile.Preset preset) {
        Profile p = plugin.profiles().get(viewer);
        plugin.weapons().flush(viewer);
        // Return current gear to the stash first.
        List<ItemRecord> current = new ArrayList<>(plugin.carry().snapshot(viewer).values());
        int footprint = current.stream().mapToInt(ItemRecord::footprint).sum();
        if (p.stashUsed() + footprint > p.stashCapacity) {
            Notify.error(viewer, "仓库空间不足，无法暂存当前装备。");
            return;
        }
        p.stash.addAll(current);
        for (int slot = 0; slot <= CarryService.HEAD; slot++) {
            if (slot != CarryService.TERMINAL_SLOT) {
                viewer.getInventory().setItem(slot, null);
            }
        }
        plugin.carry().refreshLocks(viewer);
        long spent = 0;
        List<String> missing = new ArrayList<>();
        // Equipment first so container cells unlock before the rest is placed.
        List<Map.Entry<Integer, String>> order = new ArrayList<>(preset.slots.entrySet());
        order.sort((a, b) -> Integer.compare(b.getKey() >= CarryService.FEET ? 1 : 0, a.getKey() >= CarryService.FEET ? 1 : 0));
        for (Map.Entry<Integer, String> e : order) {
            int slot = e.getKey();
            int want = preset.amounts.getOrDefault(slot, 1);
            ItemDef def = plugin.registry().get(e.getValue());
            if (def == null) {
                missing.add(e.getValue()); // an id items.yml no longer has: nothing to take or buy
                continue;
            }
            if (!exists(slot, def)) {
                // A rig or backpack cell whose container could not be put on: nothing is taken or bought for it.
                missing.add(def.name());
                continue;
            }
            ItemStack stack = takeFromStash(p, def, want);
            if (stack == null) {
                int amount = def.isGun() ? 1 : Math.min(want, def.maxStack());
                long price = plugin.traders().buyPrice(def) * amount;
                if (!sold(def) || p.money < price) {
                    missing.add(def.name());
                    continue;
                }
                p.money -= price;
                spent += price;
                stack = plugin.items().create(def, amount);
            }
            ItemStack left = place(slot, stack, def);
            if (left != null) {
                ItemRecord back = plugin.items().toRecord(left);
                if (back != null) {
                    p.stash.add(back);
                }
                missing.add(def.name());
            }
        }
        plugin.carry().refreshLocks(viewer);
        plugin.lobby().captureLoadout(viewer);
        plugin.profiles().markDirty(viewer.getUniqueId());
        Notify.ok(viewer, "已按方案 " + Text.escape(preset.name) + " 完成配装" + (spent > 0 ? "，购买花费 ◈" + Text.money(spent) : "") + "。");
        if (!missing.isEmpty()) {
            Map<String, Integer> counted = new java.util.LinkedHashMap<>();
            missing.forEach(item -> counted.merge(item, 1, Integer::sum));
            List<String> lines = new ArrayList<>();
            counted.forEach((item, n) -> lines.add(n > 1 ? item + " ×" + n : item));
            Text.send(viewer, "<accent>没有放上（仓库没有、商人不卖、块币不足、缺少胸挂/背包或物品表里没有）：" + Text.escape(String.join("、", lines)));
        }
    }

    /** Whether the trader sells this item, i.e. a missing one can be bought. */
    private boolean sold(ItemDef def) {
        return def.tradeable() && def.rarity().ordinal() <= net.infnetwork.snowball.craftov.item.Rarity.valueOf(
                plugin.getConfig().getString("trader.max-rarity", "PURPLE")).ordinal();
    }

    /** Whether the preset's slot is there to fill: equipment always, carry cells only while usable. */
    private boolean exists(int slot, ItemDef def) {
        if (slot >= CarryService.FEET) {
            return slot <= CarryService.HEAD;
        }
        if (slot == CarryService.MELEE_SLOT) {
            return def.isMelee();
        }
        return plugin.carry().usableSlots(viewer).contains(slot);
    }

    /** Puts a stack into its preset slot (or anywhere free); returns what could not be placed. */
    private @Nullable ItemStack place(int slot, ItemStack stack, ItemDef def) {
        if (def.isContainer() && (slot == CarryService.LEGS || slot == CarryService.FEET)) {
            plugin.carry().equip(viewer, stack);
            return null;
        }
        ItemStack current = viewer.getInventory().getItem(slot);
        if (slot <= CarryService.HEAD && slot != CarryService.TERMINAL_SLOT && (current == null || current.isEmpty())) {
            viewer.getInventory().setItem(slot, stack);
            return null;
        }
        return plugin.carry().give(viewer, stack, false);
    }

    private ItemStack takeFromStash(Profile p, ItemDef def, int want) {
        for (int i = 0; i < p.stash.size(); i++) {
            ItemRecord record = p.stash.get(i);
            if (!record.id.equals(def.id()) || record.hasContents()) {
                continue;
            }
            // Never more than one stack: the rest of a record above the stack size stays in the stash.
            int take = Math.min(Math.min(want, def.maxStack()), record.amount);
            if (take >= record.amount) {
                p.stash.remove(i);
                return plugin.items().fromRecord(record);
            }
            ItemRecord part = record.copy();
            part.amount = take;
            record.amount -= take;
            return plugin.items().fromRecord(part);
        }
        return null;
    }
}
