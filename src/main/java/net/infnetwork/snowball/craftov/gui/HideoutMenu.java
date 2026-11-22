package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.economy.HideoutService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Hideout: facilities on top (click to upgrade), production and income below, recipes in a sub-page. */
final class HideoutMenu extends Menu {

    private static final int[] FACILITY_SLOTS = {10, 12, 14, 16};
    private static final int[] WORK_SLOTS = {28, 30, 32, 34};

    private final CraftovPlugin plugin;
    private HideoutService.@Nullable Facility recipes;

    HideoutMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<text>藏身处"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        if (recipes != null) {
            drawRecipes(recipes);
            return;
        }
        HideoutService hideout = plugin.hideout();
        Profile profile = plugin.profiles().get(viewer);
        for (int i = 0; i < HideoutService.FACILITIES.size(); i++) {
            HideoutService.Facility f = HideoutService.FACILITIES.get(i);
            int level = hideout.level(profile, f.id());
            List<String> lore = new ArrayList<>();
            lore.add("<muted>" + f.blurb());
            lore.add("<muted>等级 <white>" + level + "/" + f.maxLevel());
            if (f.id().equals("intel") && level > 0) {
                lore.add("<muted>产出 <accent>◈" + Text.money(hideout.intelRate(profile)) + "<muted>/小时");
            }
            if (level < f.maxLevel()) {
                lore.add("");
                lore.add("<text>升级到 Lv." + (level + 1) + "：");
                lore.add(hideout.describe(viewer, f.upgrades().get(level)));
                if (!f.id().equals("generator") && hideout.level(profile, "generator") < level + 1) {
                    lore.add("<danger>需要发电机 Lv." + (level + 1));
                }
                lore.add("<ok>点击升级");
            }
            button(FACILITY_SLOTS[i], UiItems.button(f.icon(), "<text><b>" + f.name() + " <accent>Lv." + level, lore), e -> {
                String why = hideout.upgrade(viewer, f);
                if (why == null) {
                    viewer.playSound(viewer.getLocation(), "craftov:ui.levelup", SoundCategory.MASTER, 0.8f, 1f);
                    Notify.ok(viewer, "" + f.name() + " 升级到 Lv." + hideout.level(profile, f.id()));
                } else {
                    Notify.error(viewer, "" + why);
                }
                redraw();
            });
            set(FACILITY_SLOTS[i] + 9, UiItems.filler());
            drawWork(i, f, level, profile);
        }
        set(49, UiItems.button("craftov:ui/icon_mail", "<muted>产物送到仓库邮箱", List.of("<muted>在仓库界面领取")));
    }

    private void drawWork(int i, HideoutService.Facility f, int level, Profile profile) {
        HideoutService hideout = plugin.hideout();
        int slot = WORK_SLOTS[i];
        if (f.id().equals("intel")) {
            long pending = hideout.intelPending(profile);
            button(slot, UiItems.button("craftov:ui/icon_value", "<accent><b>情报收益 ◈" + Text.money(pending),
                    List.of(level == 0 ? "<muted>建造情报中心后开始产出" : "<ok>点击领取")), e -> {
                long got = hideout.claimIntel(viewer);
                if (got > 0) {
                    viewer.playSound(viewer.getLocation(), "craftov:ui.sell", SoundCategory.MASTER, 0.8f, 1f);
                    Notify.ok(viewer, "领取情报收益 ◈" + Text.money(got));
                }
                redraw();
            });
            return;
        }
        if (f.recipes().isEmpty()) {
            return;
        }
        Profile.Craft craft = profile.crafts.get(f.id());
        if (craft == null) {
            button(slot, UiItems.button("craftov:ui/icon_start", "<text><b>" + f.name() + "：空闲",
                    List.of(level == 0 ? "<muted>先建造" + f.name() : "<ok>点击选择要制作的东西")), e -> {
                if (level > 0) {
                    recipes = f;
                    redraw();
                }
            });
            return;
        }
        HideoutService.Recipe recipe = HideoutService.recipe(f, craft.recipe);
        ItemDef out = recipe == null ? null : plugin.registry().get(recipe.output());
        long left = craft.endsAt - System.currentTimeMillis();
        String what = out == null ? craft.recipe : out.name() + " ×" + recipe.amount();
        if (left > 0) {
            set(slot, UiItems.button("craftov:ui/search_active", "<accent><b>制作中：" + Text.escape(what),
                    List.of("<muted>剩余 " + left / 60_000 + " 分 " + (left / 1000) % 60 + " 秒")));
        } else {
            button(slot, UiItems.button("craftov:ui/report_ok", "<ok><b>已完成：" + Text.escape(what), List.of("<ok>点击领取到邮箱")), e -> {
                if (hideout.collect(viewer, f)) {
                    viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", SoundCategory.MASTER, 0.8f, 1f);
                }
                redraw();
            });
        }
    }

    private void drawRecipes(HideoutService.Facility f) {
        HideoutService hideout = plugin.hideout();
        int level = hideout.level(plugin.profiles().get(viewer), f.id());
        int slot = 0;
        for (HideoutService.Recipe r : f.recipes()) {
            ItemDef out = plugin.registry().get(r.output());
            if (out == null) {
                continue;
            }
            List<String> lore = new ArrayList<>();
            lore.add("<muted>产出 <white>" + Text.escape(out.name()) + " ×" + r.amount() + " <muted>· 用时 " + r.minutes() + " 分钟");
            lore.add(hideout.describe(viewer, r.cost()));
            lore.add(level >= r.level() ? "<ok>点击开始制作" : "<danger>需要" + f.name() + " Lv." + r.level());
            var icon = plugin.items().create(out, Math.min(out.maxStack(), r.amount()));
            if (icon == null) {
                continue;
            }
            button(slot++, withLore(icon, lore), e -> {
                String why = hideout.start(viewer, f, r);
                Notify.info(viewer, why == null ? "<ok>开始制作 " + Text.escape(out.name()) : "<danger>" + why);
                if (why == null) {
                    recipes = null;
                }
                redraw();
            });
        }
        button(49, UiItems.button("craftov:ui/arrow_left", "<muted>返回", List.of()), e -> {
            recipes = null;
            redraw();
        });
    }

    private static org.bukkit.inventory.ItemStack withLore(org.bukkit.inventory.ItemStack stack, List<String> lines) {
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add(Text.mm(line));
        }
        stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.LORE, io.papermc.paper.datacomponent.item.ItemLore.lore(lore));
        return stack;
    }
}
