package net.infnetwork.snowball.craftov.cosmetic;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Knife skins (#106): another look for a kind of knife, three for each of the four knives. A skin
 * is never an item. The profile keeps which skins the player owns ({@link Profile#skins}, or all of
 * them while {@code skins.own-all} is on) and which one each kind of knife wears
 * ({@link Profile#knifeSkins}); the knife itself stays the plain item it always was, so a skin can
 * never be stored, sold, listed, put in the secure container, left in a body or lost, and changing
 * knives, dying or logging out leaves the choice where it was.
 * <p>
 * The look is the knife's item model and nothing else: whatever knife is in the melee slot gets the
 * model of its skin ({@code craftov:knife_skin/<id>}, which the resource pack has for every skin:
 * first and third person, the inspect frames, the icon and the knife on the ground) and the skin's
 * name after its own. Records keep neither, so the same knife taken out of the stash, rebuilt from
 * the loadout or handed to someone else shows its own look until it is in a melee slot again, then
 * that player's choice. A sweep every other tick puts it right, so every path a knife can take
 * into the slot is covered without each one having to know about skins.
 * <p>
 * Skins are priced in 点券 ({@link net.infnetwork.snowball.craftov.economy.PointsBank}), never in
 * 块币. Buying stays closed ({@code skins.shop-open}) until the server's global 点券 is plugged in.
 * <p>
 * The 外观 menu can preview any skin's inspect: the menu closes, the knife in the melee slot takes
 * the skin's look and is inspected once, and the menu comes back when the inspect has run its
 * course (anything that cuts it short ends the preview there).
 */
public final class SkinService implements Listener {

    /** Item definitions of the skins in the resource pack: craftov:knife_skin/&lt;id&gt;. */
    public static final String MODEL_PREFIX = "craftov:knife_skin/";
    /** Ticks between the menu closing for a preview and the inspect starting: the knife comes up first. */
    private static final int PREVIEW_DELAY = 3;

    private final CraftovPlugin plugin;
    /** Every skin, in skins.yml order (the menu's). */
    private final Map<String, SkinDef> skins = new LinkedHashMap<>();
    private final Map<UUID, Preview> previews = new HashMap<>();
    /** Knife names as MiniMessage -> parsed, for the sweep (it compares every melee knife every other tick). */
    private final Map<String, Component> names = new HashMap<>();

    /**
     * A preview of {@code skin} (null: the knife's own look) on {@code knife}: the inspect starts
     * at tick {@code start}; {@code back} reopens the menu once it has run its course.
     */
    private record Preview(String knife, @Nullable String skin, long start, Runnable back) {
    }

    public SkinService(CraftovPlugin plugin) {
        this.plugin = plugin;
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    /** Reads the catalogue from the jar; a skin of a knife items.yml does not have is left out. */
    private void load() {
        try (InputStream in = plugin.getResource("skins.yml")) {
            if (in == null) {
                plugin.getLogger().warning("skins.yml is missing from the jar: there are no knife skins.");
                return;
            }
            ConfigurationSection root = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getConfigurationSection("skins");
            if (root == null) {
                return;
            }
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                skins.put(id, new SkinDef(id, s.getString("knife", ""), s.getString("name", id), s.getString("lore", ""),
                        Math.max(0, s.getLong("price", 0))));
            }
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("Could not read skins.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ catalogue

    public Collection<SkinDef> all() {
        return skins.values();
    }

    public @Nullable SkinDef get(@Nullable String id) {
        return id == null ? null : skins.get(id);
    }

    /** The skins of one kind of knife, in menu order. */
    public List<SkinDef> forKnife(String knife) {
        List<SkinDef> out = new ArrayList<>();
        for (SkinDef skin : skins.values()) {
            if (skin.knife().equals(knife)) {
                out.add(skin);
            }
        }
        return out;
    }

    /** The knives that have skins, in menu order (only those items.yml knows as melee weapons). */
    public List<ItemDef> knives() {
        List<ItemDef> out = new ArrayList<>();
        for (SkinDef skin : skins.values()) {
            ItemDef def = plugin.registry().get(skin.knife());
            if (def != null && def.isMelee() && !out.contains(def)) {
                out.add(def);
            }
        }
        return out;
    }

    public long price(SkinDef skin) {
        return Math.max(0, plugin.getConfig().getLong("skins.prices." + skin.id(), skin.price()));
    }

    public boolean ownAll() {
        return plugin.getConfig().getBoolean("skins.own-all", true);
    }

    public boolean shopOpen() {
        return plugin.getConfig().getBoolean("skins.shop-open", false);
    }

    public boolean owns(Profile profile, SkinDef skin) {
        return ownAll() || profile.skins.contains(skin.id());
    }

    /** The skin this kind of knife wears for the player, or null for its own look (also when no longer owned). */
    public @Nullable SkinDef selected(Profile profile, String knife) {
        SkinDef skin = get(profile.knifeSkins.get(knife));
        return skin != null && skin.knife().equals(knife) && owns(profile, skin) ? skin : null;
    }

    // ------------------------------------------------------------------ choosing and buying

    /**
     * Puts {@code skin} (null: the knife's own look) on every knife of the kind {@code knife} the
     * player will carry. Null when done, otherwise why not.
     */
    public @Nullable String equip(Player player, String knife, @Nullable SkinDef skin) {
        ItemDef def = plugin.registry().get(knife);
        if (def == null || !def.isMelee()) {
            return "没有这把刀";
        }
        Profile profile = plugin.profiles().get(player);
        if (skin != null) {
            if (!skin.knife().equals(knife)) {
                return "这个皮肤不属于" + def.name();
            }
            if (!owns(profile, skin)) {
                return "还没有拥有这个皮肤";
            }
            profile.knifeSkins.put(knife, skin.id());
        } else {
            profile.knifeSkins.remove(knife);
        }
        plugin.profiles().markDirty(player.getUniqueId());
        apply(player);
        return null;
    }

    /** Buys a skin with 点券, while the shop is open. Null when bought, otherwise why not. */
    public @Nullable String buy(Player player, SkinDef skin) {
        Profile profile = plugin.profiles().get(player);
        if (owns(profile, skin)) {
            return "已经拥有这个皮肤";
        }
        if (!shopOpen()) {
            return "点券商店暂未开放";
        }
        long price = price(skin);
        String error = plugin.points().take(player.getUniqueId(), price, "knife skin " + skin.id());
        if (error != null) {
            return error;
        }
        profile.skins.add(skin.id());
        plugin.profiles().markDirty(player.getUniqueId());
        plugin.getLogger().info(player.getName() + " bought the knife skin " + skin.id() + " for " + price + " points");
        return null;
    }

    // ------------------------------------------------------------------ the look

    /**
     * Gives the knife in the player's melee slot the look it should have now: the previewed one,
     * else the player's skin for that knife, else its own. Changes the stack only when it is wrong,
     * since every change makes the client lower and raise a knife in hand.
     */
    public void apply(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack knife = inv.getItem(CarryService.MELEE_SLOT);
        ItemDef def = plugin.items().defOf(knife);
        if (knife == null || def == null || !def.isMelee()) {
            return;
        }
        SkinDef worn = selected(plugin.profiles().get(player), def.id());
        Preview preview = previews.get(player.getUniqueId());
        Key model;
        if (preview != null) {
            SkinDef skin = get(preview.skin);
            ItemDef previewed = plugin.registry().get(preview.knife);
            model = Key.key(skin != null ? skin.model() : (previewed != null ? previewed : def).model());
        } else {
            model = Key.key(worn != null ? worn.model() : def.model());
        }
        // The name says which knife this is and the skin it wears (not the one a preview shows).
        String label = def.coloredName() + (worn != null ? " <muted>· " + Text.escape(worn.name()) : "");
        Component name = names.computeIfAbsent(label, Text::mm);
        if (model.equals(knife.getData(DataComponentTypes.ITEM_MODEL)) && name.equals(knife.getData(DataComponentTypes.ITEM_NAME))) {
            return;
        }
        ItemStack copy = knife.clone();
        copy.setData(DataComponentTypes.ITEM_MODEL, model);
        copy.setData(DataComponentTypes.ITEM_NAME, name);
        inv.setItem(CarryService.MELEE_SLOT, copy);
    }

    /** True when the stack shows a skin rather than its knife's own look. */
    public static boolean skinned(@Nullable ItemStack stack) {
        Key model = stack == null || stack.isEmpty() ? null : stack.getData(DataComponentTypes.ITEM_MODEL);
        return model != null && model.asString().startsWith(MODEL_PREFIX);
    }

    // ------------------------------------------------------------------ preview

    /**
     * Shows {@code skin} (null: the knife's own look) of {@code knife} on the player's melee knife
     * and inspects it once; {@code back} runs when the inspect has run its course. Null when it
     * starts, otherwise why not.
     */
    public @Nullable String preview(Player player, String knife, @Nullable SkinDef skin, Runnable back) {
        if (plugin.raids().raidOf(player) != null) {
            return "行动中无法预览";
        }
        if (!plugin.carry().isMelee(player.getInventory().getItem(CarryService.MELEE_SLOT))) {
            return "近战格里没有刀";
        }
        ItemDef def = plugin.registry().get(knife);
        if (def == null || !def.isMelee() || skin != null && !skin.knife().equals(knife)) {
            return "没有这把刀";
        }
        long start = Bukkit.getCurrentTick() + PREVIEW_DELAY;
        Preview preview = new Preview(knife, skin == null ? null : skin.id(), start, back);
        previews.put(player.getUniqueId(), preview);
        player.closeInventory();
        player.getInventory().setHeldItemSlot(CarryService.MELEE_SLOT);
        apply(player);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (previews.get(player.getUniqueId()) != preview || !player.isOnline()) {
                return;
            }
            if (player.getInventory().getHeldItemSlot() != CarryService.MELEE_SLOT
                    || plugin.inspect().inspectNow(player) != null) {
                end(player);
            }
        }, PREVIEW_DELAY);
        return null;
    }

    public boolean previewing(Player player) {
        return previews.containsKey(player.getUniqueId());
    }

    /** Ends a preview: the knife takes its own look (or the player's skin) again. */
    private void end(Player player) {
        if (previews.remove(player.getUniqueId()) != null) {
            apply(player);
        }
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        int inspectTicks = Math.max(1, plugin.getConfig().getInt("combat.melee.inspect-ticks", 50));
        for (Iterator<Map.Entry<UUID, Preview>> it = previews.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Preview> entry = it.next();
            Preview preview = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            // Over once no inspect runs any more: run to the end (the menu comes back), or cut short.
            if (now > preview.start && !plugin.inspect().inspecting(player)) {
                it.remove();
                apply(player);
                boolean ranOut = now - preview.start >= inspectTicks - 1;
                if (ranOut && plugin.raids().raidOf(player) == null
                        && player.getOpenInventory().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
                    preview.back.run();
                }
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        previews.remove(event.getPlayer().getUniqueId());
    }
}
