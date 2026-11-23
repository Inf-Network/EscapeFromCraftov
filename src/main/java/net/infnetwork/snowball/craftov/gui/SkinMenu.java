package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.cosmetic.SkinDef;
import net.infnetwork.snowball.craftov.cosmetic.SkinService;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 外观 (#106), on the "skins_&lt;knife&gt;" screens (tools/art/screens.py, skin_screen): the knives with
 * skins as tabs down the left, and a card for each look of the chosen knife, its own first. The
 * background draws each look at twice an item's size with its name; the card's cells are one
 * button (left click wears it, right click previews its inspect), and under it are a well for its
 * state (worn, ready to wear, or locked with its 点券 price) and one for the preview. The 点券 shop
 * and balance are at the right end of the top band.
 */
final class SkinMenu extends Menu {

    /** First column of each card (cards are two columns wide, rows 1 to 3, state and preview in row 4). */
    private static final int[] CARD_COLUMNS = {1, 3, 5, 7};

    private final CraftovPlugin plugin;
    private String knife;

    SkinMenu(CraftovPlugin plugin, Player viewer, @Nullable String knife) {
        super(viewer, 6, Text.mm("外观"));
        this.plugin = plugin;
        List<ItemDef> knives = plugin.skins().knives();
        String start = knife;
        if (start == null || knives.stream().noneMatch(d -> d.id().equals(knife))) {
            // The knife in the melee slot, when it has skins; the first knife otherwise.
            ItemDef held = plugin.items().defOf(viewer.getInventory().getItem(CarryService.MELEE_SLOT));
            start = held != null && knives.contains(held) ? held.id() : knives.isEmpty() ? "" : knives.get(0).id();
        }
        this.knife = start;
    }

    @Override
    protected void draw() {
        SkinService skins = plugin.skins();
        Profile profile = plugin.profiles().get(viewer);
        ItemDef def = plugin.registry().get(knife);
        restyle("skins_" + knife, Text.mm("外观 <muted>· " + (def == null ? "" : Text.escape(def.name()))));
        if (def == null) {
            closeButton(53);
            return;
        }
        // Tabs.
        ItemDef carried = plugin.items().defOf(viewer.getInventory().getItem(CarryService.MELEE_SLOT));
        List<ItemDef> knives = skins.knives();
        for (int i = 0; i < knives.size() && i < 4; i++) {
            ItemDef k = knives.get(i);
            SkinDef worn = skins.selected(profile, k.id());
            List<String> lore = new ArrayList<>();
            lore.add("<muted>当前外观 <white>" + (worn == null ? "默认" : Text.escape(worn.name())));
            if (carried != null && carried.id().equals(k.id())) {
                lore.add("<ok>近战格里就是这把刀");
            }
            lore.add(k.id().equals(knife) ? "<accent>正在查看" : "<muted>点击查看它的皮肤");
            // The tab shows the knife as the player wears it.
            String model = worn != null ? worn.model() : k.model();
            tabButton(9 + 9 * i, UiItems.button(model, k.coloredName(), lore), e -> {
                knife = k.id();
                redraw();
            });
        }
        // Cards: the knife's own look, then its skins.
        List<SkinDef> looks = new ArrayList<>();
        looks.add(null);
        looks.addAll(skins.forKnife(knife));
        SkinDef worn = skins.selected(profile, knife);
        for (int i = 0; i < looks.size() && i < CARD_COLUMNS.length; i++) {
            card(CARD_COLUMNS[i], def, looks.get(i), profile, worn);
        }
        // 点券 shop and balance.
        button(7, UiItems.button("craftov:ui/icon_points_shop", "<text><b>点券商店", skins.shopOpen()
                ? List.of("<muted>没有的皮肤点它下方的锁购买", "<muted>点券是服务器的全局货币，块币不能兑换")
                : List.of("<danger>点券商店暂未开放", "<muted>点券是服务器的全局货币，块币不能兑换")), e -> {
            if (skins.shopOpen()) {
                flash("<muted>点皮肤下方的锁购买", null);
            } else {
                flash("<danger>点券商店暂未开放", false);
            }
        });
        long points = plugin.points().balance(viewer.getUniqueId());
        List<String> balance = new ArrayList<>(List.of("<muted>服务器的全局货币，块币不能兑换"));
        if (skins.ownAll()) {
            balance.add("<ok>点券商店开放前，所有皮肤都已解锁");
        }
        set(8, UiItems.button("craftov:ui/icon_points", "<info><b>点券 " + (points < 0 ? "—" : Text.money(points)), balance));
        // Bottom bar.
        backButton(45, () -> plugin.menus().openMain(viewer));
        set(49, UiItems.button("craftov:ui/icon_help", "<text><b>关于外观", List.of(
                "<muted>皮肤只改变刀的样子：第一、第三人称、检视动作、掉在地上",
                "<muted>不是物品：不进仓库、交易行、安全箱，阵亡也不会丢",
                "<muted>每种刀各选一套，换刀、阵亡、重新登录都保留",
                "<muted>左键卡片装备 · 右键卡片或点眼睛预览检视")));
        closeButton(53);
    }

    /** One look's card: the art (background) under an invisible button, its state and its preview below. */
    private void card(int column, ItemDef def, @Nullable SkinDef skin, Profile profile, @Nullable SkinDef worn) {
        SkinService skins = plugin.skins();
        boolean owned = skin == null || skins.owns(profile, skin);
        boolean wearing = skin == null ? worn == null : worn != null && worn.id().equals(skin.id());
        String name = skin == null ? "默认" : skin.name();
        List<String> lore = new ArrayList<>();
        if (skin == null) {
            lore.add("<muted>" + Text.escape(def.name()) + "本来的样子");
        } else {
            lore.addAll(Text.wrap("<muted>" + Text.escape(skin.lore()), 40));
            lore.add("<muted>价格 <info>点券 " + Text.money(skins.price(skin)));
        }
        lore.add(wearing ? "<accent>正在使用" : owned ? "<ok>已拥有" : "<dim>未拥有");
        lore.add("<muted>左键" + (wearing ? "（已装备）" : owned ? "装备" : "购买") + " · 右键预览检视");
        String title = "<text><b>" + Text.escape(name) + "</b> <muted>" + Text.escape(def.name());
        int[] cells = {9 + column, 10 + column, 18 + column, 19 + column, 27 + column, 28 + column};
        for (int slot : cells) {
            button(slot, UiItems.button("craftov:ui/blank", title, lore), e -> {
                if (e.isRightClick()) {
                    preview(skin);
                } else {
                    choose(skin, owned, wearing);
                }
            });
        }
        ItemStack state;
        if (wearing) {
            state = UiItems.button("craftov:ui/skin_on", "<accent><b>使用中", List.of("<muted>" + Text.escape(def.name()) + "现在的外观"));
        } else if (owned) {
            state = UiItems.button("craftov:ui/skin_off", "<text><b>装备", List.of("<muted>点击换上" + Text.escape(name)));
        } else {
            state = UiItems.button("craftov:ui/locked", "<muted>未拥有 · <info>点券 " + Text.money(skins.price(skin)),
                    List.of(skins.shopOpen() ? "<muted>点击用点券购买" : "<danger>点券商店暂未开放"));
        }
        button(36 + column, state, e -> choose(skin, owned, wearing));
        button(37 + column, UiItems.button("craftov:ui/icon_inspect", "<text><b>预览检视", List.of(
                "<muted>关闭界面，在第一人称看一遍" + Text.escape(name) + "的检视动作",
                "<muted>看完回到这里；切换快捷栏或冲刺会提前结束")), e -> preview(skin));
    }

    private void choose(@Nullable SkinDef skin, boolean owned, boolean wearing) {
        if (wearing) {
            flash("<muted>已经在使用", null);
            return;
        }
        boolean bought = false;
        if (!owned) {
            String error = plugin.skins().buy(viewer, skin);
            if (error != null) {
                flash("<danger>" + Text.escape(error), false);
                return;
            }
            bought = true;
        }
        String error = plugin.skins().equip(viewer, knife, skin);
        if (error != null) {
            flash("<danger>" + Text.escape(error), false);
            return;
        }
        redraw();
        flash("<ok>" + (bought ? "已购买并装备 " : "已装备 ") + (skin == null ? "默认外观" : Text.escape(skin.name())), true);
    }

    private void preview(@Nullable SkinDef skin) {
        String shown = knife;
        String error = plugin.skins().preview(viewer, shown, skin, () -> new SkinMenu(plugin, viewer, shown).open());
        if (error != null) {
            flash("<danger>" + Text.escape(error), false);
        }
    }

    @Override
    protected void onBottomClick(InventoryClickEvent event) {
        // The player's own items stay where they are: a skin is not an item to drop in here.
    }
}
