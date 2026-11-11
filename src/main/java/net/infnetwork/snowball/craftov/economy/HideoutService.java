package net.infnetwork.snowball.craftov.economy;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The hideout: facilities built and upgraded with money and salvage. The generator gates the rest;
 * the workbench turns parts into ammunition, the med station turns supplies into medicine, both in
 * real time (it keeps running while offline), and the intel centre earns money by the hour.
 * Materials come from the stash first, then from what the player carries; products go to the mailbox.
 */
public final class HideoutService {

    public record Cost(long money, Map<String, Integer> items) {
    }

    public record Recipe(String id, int level, Cost cost, String output, int amount, int minutes) {
    }

    public record Facility(String id, String name, String icon, String blurb, List<Cost> upgrades, List<Recipe> recipes) {
        public int maxLevel() {
            return upgrades.size();
        }
    }

    private static Map<String, Integer> items(Object... pairs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    public static final List<Facility> FACILITIES = List.of(
            new Facility("generator", "发电机", "craftov:ui/icon_upgrade", "其他设施的等级不能超过发电机", List.of(
                    new Cost(20_000, items("battery", 3, "copper_coil", 2)),
                    new Cost(60_000, items("battery_pack", 1, "toolset", 1)),
                    new Cost(150_000, items("reactor_cell", 1))), List.of()),
            new Facility("workbench", "工作台", "craftov:ui/tab_ammo", "用零件和块币制作弹药", List.of(
                    new Cost(15_000, items("screws", 4, "toolset", 1)),
                    new Cost(50_000, items("multimeter", 1, "circuit", 2)),
                    new Cost(120_000, items("optics", 1, "gpu", 1))), List.of(
                    new Recipe("w_556", 1, new Cost(1_000, items("screws", 2)), "ammo_556_m855", 60, 20),
                    new Recipe("w_762", 1, new Cost(1_000, items("screws", 2)), "ammo_762_ps", 60, 20),
                    new Recipe("w_9", 1, new Cost(800, items("screws", 1)), "ammo_9_fmj", 90, 15),
                    new Recipe("w_12", 1, new Cost(800, items("screws", 1)), "ammo_12_buck", 40, 15),
                    new Recipe("w_556a1", 2, new Cost(4_000, items("circuit", 1)), "ammo_556_a1", 60, 40),
                    new Recipe("w_762bp", 2, new Cost(4_000, items("circuit", 1)), "ammo_762_bp", 60, 40),
                    new Recipe("w_9ap", 2, new Cost(3_000, items("circuit", 1)), "ammo_9_ap", 60, 30),
                    new Recipe("w_m80", 2, new Cost(4_000, items("circuit", 1)), "ammo_308_m80", 40, 40),
                    new Recipe("w_m995", 3, new Cost(12_000, items("battery_pack", 1)), "ammo_556_m995", 40, 90),
                    new Recipe("w_762ap", 3, new Cost(12_000, items("battery_pack", 1)), "ammo_762_ap", 40, 90),
                    new Recipe("w_m61", 3, new Cost(12_000, items("battery_pack", 1)), "ammo_308_m61", 30, 90),
                    new Recipe("w_338", 3, new Cost(12_000, items("battery_pack", 1)), "ammo_338_fmj", 20, 90))),
            new Facility("medstation", "医疗站", "craftov:ui/tab_medical", "用物资和块币制作药品", List.of(
                    new Cost(15_000, items("water_tabs", 3, "tape", 2)),
                    new Cost(50_000, items("reagent", 1, "ration", 2)),
                    new Cost(120_000, items("sample_case", 1))), List.of(
                    new Recipe("m_bandage", 1, new Cost(300, items("tape", 1)), "bandage", 3, 10),
                    new Recipe("m_hemostat", 1, new Cost(800, items("water_tabs", 2)), "hemostat", 2, 20),
                    new Recipe("m_medkit", 2, new Cost(2_000, items("reagent", 1)), "medkit_s", 2, 30),
                    new Recipe("m_painkiller", 2, new Cost(2_000, items("reagent", 1)), "painkiller", 2, 30),
                    new Recipe("m_medkit_l", 3, new Cost(6_000, items("reagent", 2)), "medkit_l", 1, 60),
                    new Recipe("m_surgery", 3, new Cost(6_000, items("reagent", 2)), "surgery", 1, 60),
                    new Recipe("m_adrenaline", 3, new Cost(6_000, items("reagent", 2)), "adrenaline", 1, 60))),
            new Facility("intel", "情报中心", "craftov:ui/icon_map", "每小时产出块币，最多累积 24 小时", List.of(
                    new Cost(30_000, items("radio", 1, "battery", 4)),
                    new Cost(80_000, items("laptop", 1, "sat_phone", 1)),
                    new Cost(200_000, items("server_drive", 1, "dossier", 1))), List.of()));

    /** Intel centre income per hour by level. */
    private static final long[] INCOME = {0, 2_000, 5_000, 10_000};

    private final CraftovPlugin plugin;

    public HideoutService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public static @Nullable Facility facility(String id) {
        for (Facility facility : FACILITIES) {
            if (facility.id().equals(id)) {
                return facility;
            }
        }
        return null;
    }

    public int level(Profile profile, String facility) {
        return profile.hideout.getOrDefault(facility, 0);
    }

    // ------------------------------------------------------------------ materials

    /** How many of an item the player has in the stash and on them. */
    public int have(Player player, String id) {
        Profile profile = plugin.profiles().get(player);
        int n = 0;
        for (ItemRecord record : profile.stash) {
            if (id.equals(record.id)) {
                n += record.amount;
            }
        }
        for (org.bukkit.inventory.ItemStack stack : player.getInventory().getStorageContents()) {
            ItemDef def = plugin.items().defOf(stack);
            if (def != null && def.id().equals(id)) {
                n += stack.getAmount();
            }
        }
        return n;
    }

    public boolean affordable(Player player, Cost cost) {
        if (plugin.profiles().get(player).money < cost.money()) {
            return false;
        }
        for (Map.Entry<String, Integer> e : cost.items().entrySet()) {
            if (have(player, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void pay(Player player, Cost cost) {
        Profile profile = plugin.profiles().get(player);
        profile.money -= cost.money();
        for (Map.Entry<String, Integer> e : cost.items().entrySet()) {
            int need = e.getValue();
            for (Iterator<ItemRecord> it = profile.stash.iterator(); it.hasNext() && need > 0; ) {
                ItemRecord record = it.next();
                if (!e.getKey().equals(record.id)) {
                    continue;
                }
                int take = Math.min(need, record.amount);
                record.amount -= take;
                need -= take;
                if (record.amount <= 0) {
                    it.remove();
                }
            }
            if (need > 0) {
                plugin.carry().takeItem(player, e.getKey(), need);
            }
        }
        plugin.profiles().markDirty(player.getUniqueId());
    }

    /** "◈20,000 · 一号电池 ×3 (2/3)" with red for what is missing. */
    public String describe(Player player, Cost cost) {
        Profile profile = plugin.profiles().get(player);
        StringBuilder out = new StringBuilder((profile.money >= cost.money() ? "<#F2C94C>" : "<#FF8A80>") + "◈" + Text.money(cost.money()));
        for (Map.Entry<String, Integer> e : cost.items().entrySet()) {
            ItemDef def = plugin.registry().get(e.getKey());
            int has = have(player, e.getKey());
            out.append(" <#8A8F98>· ").append(has >= e.getValue() ? "<#C8CCD2>" : "<#FF8A80>")
                    .append(def == null ? e.getKey() : Text.escape(def.name())).append(" ×").append(e.getValue())
                    .append(" <#8A8F98>(").append(has).append(")");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ actions

    /** Null on success, otherwise why not. */
    public @Nullable String upgrade(Player player, Facility facility) {
        Profile profile = plugin.profiles().get(player);
        int level = level(profile, facility.id());
        if (level >= facility.maxLevel()) {
            return "已经满级";
        }
        if (!facility.id().equals("generator") && level(profile, "generator") < level + 1) {
            return "需要发电机 Lv." + (level + 1);
        }
        Cost cost = facility.upgrades().get(level);
        if (!affordable(player, cost)) {
            return "材料或块币不足";
        }
        pay(player, cost);
        if (facility.id().equals("intel") && level == 0) {
            profile.intelSince = System.currentTimeMillis();
        } else if (facility.id().equals("intel")) {
            claimIntel(player); // bank what the old level earned before the rate changes
        }
        profile.hideout.put(facility.id(), level + 1);
        plugin.profiles().markDirty(player.getUniqueId());
        return null;
    }

    public @Nullable String start(Player player, Facility facility, Recipe recipe) {
        Profile profile = plugin.profiles().get(player);
        if (level(profile, facility.id()) < recipe.level()) {
            return "需要" + facility.name() + " Lv." + recipe.level();
        }
        if (profile.crafts.containsKey(facility.id())) {
            return "正在制作中";
        }
        if (!affordable(player, recipe.cost())) {
            return "材料或块币不足";
        }
        pay(player, recipe.cost());
        Profile.Craft craft = new Profile.Craft();
        craft.recipe = recipe.id();
        craft.endsAt = System.currentTimeMillis() + recipe.minutes() * 60_000L;
        profile.crafts.put(facility.id(), craft);
        plugin.profiles().markDirty(player.getUniqueId());
        return null;
    }

    public static @Nullable Recipe recipe(Facility facility, String id) {
        for (Recipe recipe : facility.recipes()) {
            if (recipe.id().equals(id)) {
                return recipe;
            }
        }
        return null;
    }

    /** Collects a finished product into the mailbox; false if nothing is ready. */
    public boolean collect(Player player, Facility facility) {
        Profile profile = plugin.profiles().get(player);
        Profile.Craft craft = profile.crafts.get(facility.id());
        if (craft == null || System.currentTimeMillis() < craft.endsAt) {
            return false;
        }
        Recipe recipe = recipe(facility, craft.recipe);
        profile.crafts.remove(facility.id());
        if (recipe != null) {
            mail(profile, recipe.output(), recipe.amount());
        }
        plugin.profiles().markDirty(player.getUniqueId());
        return true;
    }

    private void mail(Profile profile, String id, int amount) {
        ItemDef def = plugin.registry().get(id);
        int stack = def == null ? amount : Math.max(1, def.maxStack());
        while (amount > 0) {
            int n = Math.min(stack, amount);
            ItemRecord record = new ItemRecord(id, n);
            profile.mailbox.add(record);
            amount -= n;
        }
    }

    /** Money the intel centre has earned since it was last emptied (capped at a day). */
    public long intelPending(Profile profile) {
        int level = level(profile, "intel");
        if (level <= 0 || profile.intelSince <= 0) {
            return 0;
        }
        double hours = Math.min(24, (System.currentTimeMillis() - profile.intelSince) / 3_600_000.0);
        return Math.round(INCOME[Math.min(level, INCOME.length - 1)] * hours);
    }

    public long intelRate(Profile profile) {
        return INCOME[Math.min(level(profile, "intel"), INCOME.length - 1)];
    }

    public long claimIntel(Player player) {
        Profile profile = plugin.profiles().get(player);
        long amount = intelPending(profile);
        profile.money += amount;
        profile.intelSince = System.currentTimeMillis();
        plugin.profiles().markDirty(player.getUniqueId());
        return amount;
    }
}
