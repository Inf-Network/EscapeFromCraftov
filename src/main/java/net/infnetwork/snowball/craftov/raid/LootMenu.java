package net.infnetwork.snowball.craftov.raid;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.gui.Menu;
import net.infnetwork.snowball.craftov.gui.UiItems;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.Rarity;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Search screen for a {@link LootContainer}. Items are revealed one by one while it is open. */
public final class LootMenu extends Menu {

    private final CraftovPlugin plugin;
    private final LootContainer container;
    private int lastProgressStep = -1;

    public LootMenu(CraftovPlugin plugin, Player viewer, LootContainer container) {
        super(viewer, container.type.rows(), Text.mm("<#E8E8E8>" + Text.escape(container.title)
                + (container.hiddenCount() > 0 ? "  <#8A8F98>搜索中…" : "")));
        this.plugin = plugin;
        this.container = container;
    }

    @Override
    protected void draw() {
        int next = container.nextHidden();
        for (int slot = 0; slot < container.size(); slot++) {
            ItemStack stack = container.get(slot);
            if (stack == null) {
                continue;
            }
            if (container.isRevealed(slot)) {
                set(slot, stack);
            } else if (slot == next) {
                int percent = (int) Math.round(container.progress() * 100);
                set(slot, UiItems.button("craftov:ui/search_active", "<#F2C94C>搜索中… " + percent + "%",
                        List.of("<#8A8F98>保持界面打开以继续搜索"), "search"));
            } else {
                set(slot, UiItems.button("craftov:ui/search_hidden", "<#6B7079>未搜索", List.of(), "search"));
            }
        }
    }

    @Override
    public boolean ticking() {
        return true;
    }

    @Override
    public void tick() {
        if (!viewer.isOnline() || viewer.getWorld() != container.location.getWorld()
                || viewer.getLocation().distanceSquared(container.location) > 49 || plugin.downed().isDowned(viewer)) {
            viewer.closeInventory();
            return;
        }
        if (container.hiddenCount() == 0) {
            return;
        }
        double speed = plugin.operators().searchMultiplier(viewer);
        int revealed = container.searchTick(speed, Bukkit.getCurrentTick());
        if (revealed >= 0) {
            ItemDef def = plugin.items().defOf(container.get(revealed));
            Rarity rarity = def == null ? Rarity.WHITE : def.rarity();
            String sound = switch (rarity) {
                case GOLD -> "craftov:loot.reveal_gold";
                case RED -> "craftov:loot.reveal_red";
                case PURPLE -> "craftov:loot.reveal_purple";
                default -> "craftov:loot.reveal";
            };
            viewer.playSound(viewer.getLocation(), sound, SoundCategory.PLAYERS, 0.8f, 1f);
            if (rarity == Rarity.RED && def != null) {
                viewer.showTitle(net.kyori.adventure.title.Title.title(Text.mm(""),
                        Text.mm(rarity.tag() + "★ 发现 " + Text.escape(def.name()) + " ★")));
            }
            redraw();
            lastProgressStep = -1;
            return;
        }
        int step = (int) (container.progress() * 10);
        if (step != lastProgressStep) {
            lastProgressStep = step;
            int next = container.nextHidden();
            if (next >= 0) {
                int percent = (int) Math.round(container.progress() * 100);
                set(next, UiItems.button("craftov:ui/search_active", "<#F2C94C>搜索中… " + percent + "%",
                        List.of("<#8A8F98>保持界面打开以继续搜索"), "search"));
            }
            if (Bukkit.getCurrentTick() % 4 == 0) {
                viewer.playSound(viewer.getLocation(), "craftov:loot.search_tick", SoundCategory.PLAYERS, 0.25f, 1f);
            }
        }
    }

    @Override
    protected void onTopClick(InventoryClickEvent event) {
        int slot = event.getSlot();
        if (slot < 0 || slot >= container.size() || !container.isRevealed(slot)) {
            return;
        }
        ItemStack stack = container.get(slot);
        if (stack == null) {
            return;
        }
        plugin.weapons().flush(viewer);
        CarryService carry = plugin.carry();
        ItemDef def = plugin.items().defOf(stack);
        if (def != null && def.isContainer() && (ItemFactory.hasContents(stack) || event.isRightClick())) {
            int armorSlot = CarryService.containerSlot(def.category());
            ItemStack old = carry.unequip(viewer, armorSlot);
            container.set(slot, old);
            carry.equip(viewer, stack);
            viewer.playSound(viewer.getLocation(), "minecraft:item.armor.equip_leather", 1f, 1f);
            redraw();
            return;
        }
        if (def != null && def.isArmor() && event.isRightClick()) {
            int armorSlot = def.category() == net.infnetwork.snowball.craftov.item.Category.HELMET ? CarryService.HEAD : CarryService.CHEST;
            ItemStack old = viewer.getInventory().getItem(armorSlot);
            viewer.getInventory().setItem(armorSlot, stack);
            container.set(slot, old == null || old.isEmpty() ? null : old);
            viewer.playSound(viewer.getLocation(), "minecraft:item.armor.equip_iron", 1f, 1f);
            redraw();
            return;
        }
        ItemStack left = carry.give(viewer, stack, false);
        if (left != null && left.getAmount() == stack.getAmount()) {
            viewer.sendActionBar(Text.mm("<#FF8A80>携行空间不足"));
            viewer.playSound(viewer.getLocation(), "minecraft:block.note_block.bass", 0.6f, 0.6f);
            return;
        }
        container.set(slot, left);
        viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", SoundCategory.PLAYERS, 0.7f, 1f);
        plugin.raids().onLooted(viewer, stack);
        redraw();
    }

    @Override
    protected void onBottomClick(InventoryClickEvent event) {
        ItemStack stack = event.getCurrentItem();
        if (stack == null || stack.isEmpty() || ItemFactory.isUi(stack)) {
            return;
        }
        int target = container.firstEmpty();
        if (target < 0) {
            viewer.sendActionBar(Text.mm("<#FF8A80>容器已满"));
            return;
        }
        plugin.weapons().flush(viewer);
        ItemStack moving = event.getClickedInventory().getItem(event.getSlot());
        if (moving == null || moving.isEmpty()) {
            return;
        }
        container.set(target, moving.clone());
        event.getClickedInventory().setItem(event.getSlot(), null);
        viewer.playSound(viewer.getLocation(), "craftov:loot.pickup", SoundCategory.PLAYERS, 0.5f, 0.8f);
        redraw();
    }
}
