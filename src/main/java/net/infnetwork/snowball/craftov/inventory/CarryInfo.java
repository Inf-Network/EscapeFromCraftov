package net.infnetwork.snowball.craftov.inventory;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.gui.UiItems;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Nms;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The inventory screen's info cells (#10). Vanilla's E screen cannot be replaced, but its 2x2
 * crafting grid and result slot are never used: they show five cells that exist only in the packets
 * the player is sent (Nms#selfCrafting), so nothing shown there can ever become a real item.
 * <pre>
 *  raw 0 (result)  protection: the armour worn, its class as the count
 *  raw 1           load: a gauge (green, amber past the comfortable load, red past the overload), kg as the count
 *  raw 2           carried value, gear value, insured items
 *  raw 3           the secure container: click to open it
 *  raw 4           tidy: stacks merge, the rig and backpack sort themselves
 * </pre>
 * The cells are rebuilt every half second and sent again only when they change. A click on one is a
 * click on the crafting grid, which CarryListener cancels and hands here. The search screen shows the
 * same load and value cells (LootMenu).
 */
public final class CarryInfo implements Listener {

    public static final int PROTECTION = 0;
    public static final int LOAD = 1;
    public static final int VALUE = 2;
    public static final int SECURE = 3;
    public static final int TIDY = 4;
    /** Gauge frames of the load cell (tools/art/ui.py, WEIGHT_LEVELS). */
    private static final int LOAD_LEVELS = 9;

    private final CraftovPlugin plugin;
    private final Map<UUID, ItemStack[]> shown = new HashMap<>();

    public CarryInfo(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                refresh(player);
            }
        }, 20L, 10L);
    }

    private static boolean managed(Player player) {
        return player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR;
    }

    /** Builds the five cells and sends the ones that changed since the last time. */
    public void refresh(Player player) {
        Nms.SelfView view = plugin.fov().selfView(player);
        if (view == null) {
            return;
        }
        ItemStack[] before = shown.get(player.getUniqueId());
        if (!managed(player)) {
            // A creative inventory would take a shown cell as a real item: the grid shows what is there.
            if (before != null) {
                shown.remove(player.getUniqueId());
                Nms.selfCrafting(view, null);
                Nms.resendOwnSlots(player, 0, 1, 2, 3, 4);
            }
            return;
        }
        ItemStack[] cells = {protection(player), load(player), value(player), secure(player), tidy()};
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < cells.length; i++) {
            if (before == null || !same(before[i], cells[i])) {
                changed.add(i);
            }
        }
        if (changed.isEmpty() || !Nms.selfCrafting(view, cells)) {
            return;
        }
        shown.put(player.getUniqueId(), cells);
        Nms.resendOwnSlots(player, changed.stream().mapToInt(Integer::intValue).toArray());
    }

    private static boolean same(@Nullable ItemStack a, ItemStack b) {
        return a != null && a.getAmount() == b.getAmount() && a.isSimilar(b);
    }

    /** Sends the cells again a tick from now: the client acted a click on one out, or closed the screen. */
    public void resend(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && shown.containsKey(player.getUniqueId())) {
                Nms.resendOwnSlots(player, 0, 1, 2, 3, 4);
            }
        });
    }

    /** A click on the crafting grid of the player's own screen (raw slot 0..4). */
    public void clicked(Player player, int raw, ClickType click) {
        resend(player);
        if (click == ClickType.DOUBLE_CLICK || click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            return;
        }
        switch (raw) {
            case SECURE -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    plugin.menus().openSecure(player);
                }
            });
            case TIDY -> {
                if (!player.getItemOnCursor().isEmpty()) {
                    Notify.error(player, "请先放下手上的物品再整理");
                    return;
                }
                int freed = plugin.carry().tidy(player);
                player.updateInventory();
                player.playSound(player.getLocation(), "craftov:loot.pickup", 0.6f, 1.2f);
                Notify.ok(player, freed > 0 ? "已整理胸挂与背包，合并后空出 " + freed + " 格" : "已整理胸挂与背包");
            }
            default -> {
                // The other cells only show something.
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ the cells

    /** Protection: helmet and body armour with class and durability; the count is the vest's class. */
    public ItemStack protection(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemFactory items = plugin.items();
        ItemDef helmet = items.defOf(inv.getItem(CarryService.HEAD));
        ItemDef vest = items.defOf(inv.getItem(CarryService.CHEST));
        List<String> lore = new ArrayList<>();
        lore.add(armourLine("头盔", inv.getItem(CarryService.HEAD), helmet));
        lore.add(armourLine("护甲", inv.getItem(CarryService.CHEST), vest));
        ItemDef rig = items.defOf(inv.getItem(CarryService.LEGS));
        ItemDef pack = items.defOf(inv.getItem(CarryService.FEET));
        lore.add("<muted>胸挂 " + (rig == null ? "<dim>未装备" : rig.coloredName() + " <muted>" + plugin.carry().rigCapacity(player) + " 格"));
        lore.add("<muted>背包 " + (pack == null ? "<dim>未装备" : pack.coloredName() + " <muted>" + plugin.carry().backpackCapacity(player) + " 格"));
        int armourClass = vest != null ? vest.armorClass() : helmet != null ? helmet.armorClass() : 0;
        String name = vest == null && helmet == null ? "<text>防护 <dim>无护甲"
                : "<text>防护 <muted>护甲 <white>" + (vest == null ? "-" : vest.armorClass() + "级")
                + " <muted>头盔 <white>" + (helmet == null ? "-" : helmet.armorClass() + "级");
        ItemStack stack = UiItems.button("craftov:ui/info_armor", name, lore, "info");
        if (armourClass > 1) {
            stack.setData(DataComponentTypes.MAX_STACK_SIZE, 99);
            stack.setAmount(armourClass);
        }
        return stack;
    }

    private String armourLine(String what, @Nullable ItemStack stack, @Nullable ItemDef def) {
        if (def == null) {
            return "<muted>" + what + " <dim>未装备";
        }
        double dura = ItemFactory.dura(stack, def.durability());
        return "<muted>" + what + " " + def.coloredName() + " <muted>" + def.armorClass() + "级 · 耐久 <white>"
                + Math.round(dura) + "/" + Math.round(def.durability());
    }

    /** Load: the gauge frame in custom_model_data float 0 and the weight in kg as the count. */
    public ItemStack load(Player player) {
        double kg = plugin.stamina().weight(player);
        double comfort = plugin.stamina().comfortKg();
        double overload = Math.max(comfort + 1, plugin.stamina().overloadKg());
        int level;
        String state;
        if (kg <= comfort) {
            level = (int) Math.ceil(5 * kg / comfort);
            state = "<ok>轻装";
        } else if (kg <= overload) {
            level = 5 + (int) Math.ceil(2 * (kg - comfort) / (overload - comfort));
            state = "<accent>超重 · 移动变慢";
        } else {
            level = LOAD_LEVELS - 1;
            state = "<danger>超载 · 无法冲刺";
        }
        level = Math.max(0, Math.min(LOAD_LEVELS - 1, level));
        double penalty = plugin.stamina().overweightPenalty(player);
        List<String> lore = new ArrayList<>(List.of(state,
                "<muted>舒适负重 <white>" + Math.round(comfort) + "kg <muted>· 超载 <white>" + Math.round(overload) + "kg"));
        if (penalty > 0) {
            lore.add("<muted>移动速度 <danger>-" + Math.round(penalty * 100) + "%");
        }
        ItemStack stack = UiItems.button("craftov:ui/info_weight", "<text>负重 <white>" + Text.decimal(kg) + "kg", lore, "info");
        stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(level).build());
        int shownKg = (int) Math.min(99, Math.round(kg));
        if (shownKg > 1) {
            stack.setData(DataComponentTypes.MAX_STACK_SIZE, 99);
            stack.setAmount(shownKg);
        }
        return stack;
    }

    /** Value: what the player carries, their gear value and how much of it is insured. */
    public ItemStack value(Player player) {
        long carried = plugin.carry().carriedValue(player);
        long gear = plugin.carry().gearValue(player);
        int insured = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(Keys.INSURED)) {
                insured++;
            }
        }
        long secure = 0;
        for (ItemRecord record : secureContents(player)) {
            secure += plugin.items().value(record);
        }
        List<String> lore = new ArrayList<>(List.of("<muted>战备值 <white>◈" + Text.money(gear) + " <dim>(不含近战)",
                "<muted>安全箱内 <white>◈" + Text.money(secure)));
        RaidPlayer rp = plugin.raids().session(player);
        if (rp != null) {
            lore.add(insured > 0 ? "<info>已投保 " + insured + " 件 <muted>· 阵亡后可能理赔" : "<dim>本局没有投保的物品");
        } else {
            lore.add("<dim>出发时可在部署界面投保");
        }
        return UiItems.button("craftov:ui/icon_value", "<text>携带价值 <accent>◈" + Text.money(carried), lore, "info");
    }

    private List<ItemRecord> secureContents(Player player) {
        RaidPlayer rp = plugin.raids().session(player);
        if (rp != null && rp.active()) {
            return rp.secure;
        }
        return plugin.profiles().get(player).secure;
    }

    /** The secure container: what is in it, and a click opens it. */
    public ItemStack secure(Player player) {
        Profile profile = plugin.profiles().get(player);
        int used = plugin.carry().secureUsed(secureContents(player));
        int cap = plugin.traders().secureSlots(profile);
        return UiItems.button("craftov:ui/icon_secure", (used > cap ? "<danger>" : "<info>") + "安全箱 <white>" + used + "/" + cap + " 格",
                List.of("<muted>阵亡不掉落", "<muted>物品按大小占格", "<ok>点击打开"), "info");
    }

    /** Tidy: one click merges stacks and sorts the rig and backpack. */
    public ItemStack tidy() {
        return UiItems.button("craftov:ui/info_tidy", "<text>一键整理",
                List.of("<muted>合并同类堆叠，按类别整理胸挂与背包", "<muted>弹药、医疗品优先放进胸挂", "<dim>快捷栏保持不动"), "info");
    }
}
