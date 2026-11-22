package net.infnetwork.snowball.craftov.loot;

import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRegistry;
import net.infnetwork.snowball.craftov.item.Rarity;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Rolls container contents from loot.yml. Every roll is shared: the container holds the same things
 * for whoever opens it. Jackpot items (loot.yml {@code jackpots}, the top collectibles) are never
 * drawn by the rarity rolls; instead every container that can hold a red item has their own, very
 * low chance of holding one more. A player's personal luck (pity, quota, loss compensation) works on
 * top of this in LuckService.
 */
public final class LootService {

    private final ItemRegistry registry;
    private final ItemFactory items;
    private final Logger logger;
    private final Map<String, ContainerType> types = new LinkedHashMap<>();
    /** Jackpot item id -> chance per container that can hold a red item, in loot.yml order. */
    private final Map<String, Double> jackpots = new LinkedHashMap<>();

    public LootService(ItemFactory items, Logger logger) {
        this.items = items;
        this.registry = items.registry();
        this.logger = logger;
    }

    public void load(File file) {
        types.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        jackpots.clear();
        ConfigurationSection jackpot = yaml.getConfigurationSection("jackpots");
        if (jackpot != null) {
            for (String id : jackpot.getKeys(false)) {
                if (registry.get(id) == null) {
                    logger.warning("loot.yml jackpots: unknown item '" + id + "', left out");
                    continue;
                }
                jackpots.put(id, Math.max(0, jackpot.getDouble(id)));
            }
        }
        ConfigurationSection root = yaml.getConfigurationSection("containers");
        if (root == null) {
            logger.severe("loot.yml has no 'containers' section");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            Map<Category, Double> categories = new EnumMap<>(Category.class);
            ConfigurationSection c = s.getConfigurationSection("categories");
            if (c != null) {
                for (String key : c.getKeys(false)) {
                    categories.put(Category.valueOf(key.toUpperCase(Locale.ROOT)), c.getDouble(key));
                }
            }
            Map<Rarity, Double> rarities = new EnumMap<>(Rarity.class);
            ConfigurationSection r = s.getConfigurationSection("rarity");
            if (r != null) {
                for (String key : r.getKeys(false)) {
                    rarities.put(Rarity.parse(key), r.getDouble(key));
                }
            }
            List<Category> allOf = new ArrayList<>();
            for (String key : s.getStringList("all-of")) {
                allOf.add(Category.valueOf(key.toUpperCase(Locale.ROOT)));
            }
            List<Integer> range = s.getIntegerList("items");
            types.put(id, new ContainerType(id, s.getString("name", id), s.getString("model", "craftov:container/" + id),
                    (float) s.getDouble("scale", 1.0), s.getDouble("hitbox-width", 0.9), s.getDouble("hitbox-height", 0.9),
                    Math.max(1, Math.min(6, s.getInt("rows", 3))),
                    range.isEmpty() ? 2 : range.get(0), range.size() < 2 ? 4 : range.get(1),
                    s.getDouble("search-speed", 1.0), categories, rarities, List.copyOf(allOf)));
        }
        logger.info("Loaded " + types.size() + " container types, " + jackpots.size() + " jackpot items");
    }

    public @Nullable ContainerType type(String id) {
        return types.get(id);
    }

    public Collection<ContainerType> types() {
        return types.values();
    }

    /** Jackpot item ids in loot.yml order. */
    public Set<String> jackpots() {
        return Collections.unmodifiableSet(jackpots.keySet());
    }

    public boolean isJackpot(@Nullable ItemDef def) {
        return def != null && jackpots.containsKey(def.id());
    }

    /**
     * Rolls items for a container. An all-of container (the proving-ground armory) holds one of every item
     * in its categories, each marked as a test item: usable in the raid, worth nothing outside it.
     *
     * @param shift quality shift: map difficulty plus container hotness (0 = baseline)
     */
    public List<ItemStack> roll(ContainerType type, int shift) {
        return roll(type, shift, null);
    }

    /**
     * {@link #roll(ContainerType, int)} with each rarity weight (after the shift) multiplied by
     * {@code boost}: a player's loss compensation, for the containers they open first.
     */
    public List<ItemStack> roll(ContainerType type, int shift, @Nullable Map<Rarity, Double> boost) {
        if (!type.allOf().isEmpty()) {
            List<ItemStack> out = new ArrayList<>();
            for (Category category : type.allOf()) {
                List<ItemDef> defs = new ArrayList<>(registry.byCategory(category));
                defs.sort(Comparator.comparing(ItemDef::rarity).thenComparingLong(ItemDef::value));
                for (ItemDef def : defs) {
                    out.add(items.markTest(items.sample(def)));
                }
            }
            return out;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int count = type.minItems() + rng.nextInt(Math.max(1, type.maxItems() - type.minItems() + 1));
        count += shift > 1 ? rng.nextInt(2) : 0;
        Map<Rarity, Double> weights = shifted(type.rarities(), shift);
        if (boost != null && !boost.isEmpty()) {
            Map<Rarity, Double> boosted = new EnumMap<>(Rarity.class);
            weights.forEach((r, w) -> boosted.put(r, w * Math.max(0, boost.getOrDefault(r, 1.0))));
            weights = boosted;
        }
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Category category = weighted(type.categories(), rng);
            Rarity rarity = weighted(weights, rng);
            if (category == null || rarity == null) {
                continue;
            }
            ItemDef def = pick(category, rarity, rng);
            if (def == null) {
                continue;
            }
            out.add(stackFor(def, rng));
        }
        if (!finds(type, shift, Rarity.RED).isEmpty()) {
            for (Map.Entry<String, Double> e : jackpots.entrySet()) {
                ItemDef def = registry.get(e.getKey());
                if (def != null && rng.nextDouble() < e.getValue()) {
                    out.add(stackFor(def, rng));
                }
            }
        }
        return out;
    }

    /**
     * The single items a roll of this container can turn up at exactly {@code rarity}: lootable,
     * one to a stack, no rounds (a stack of gold rounds is worth a few thousand) and no jackpots.
     * Empty when the container's weights leave the rarity out. A container counts towards a
     * player's pity for a rarity, and can pay it out, only when this is not empty; a container that
     * can hold a red item is also one that can hold a jackpot.
     */
    public List<ItemDef> finds(ContainerType type, int shift, Rarity rarity) {
        if (!type.allOf().isEmpty() || shifted(type.rarities(), shift).getOrDefault(rarity, 0.0) <= 0) {
            return List.of();
        }
        List<ItemDef> out = new ArrayList<>();
        for (Map.Entry<Category, Double> e : type.categories().entrySet()) {
            if (e.getValue() <= 0 || e.getKey() == Category.AMMO) {
                continue;
            }
            for (ItemDef def : registry.byCategory(e.getKey())) {
                if (def.lootable() && def.rarity() == rarity && def.maxStack() == 1 && !jackpots.containsKey(def.id())) {
                    out.add(def);
                }
            }
        }
        return out;
    }

    public ItemStack stackFor(ItemDef def, Random rng) {
        int amount = 1;
        if (def.category() == Category.AMMO) {
            amount = Math.min(def.maxStack(), 10 + rng.nextInt(31));
        } else if (def.maxStack() > 1 && def.category() != Category.MEDICAL) {
            amount = 1 + rng.nextInt(Math.min(3, def.maxStack()));
        }
        ItemStack stack = items.create(def, amount);
        if (def.isArmor() && rng.nextDouble() < 0.6) {
            items.setDura(stack, Math.max(1, def.durability() * (0.35 + rng.nextDouble() * 0.6)));
        }
        if (def.isGun() && rng.nextDouble() < 0.5) {
            List<ItemDef> ammo = registry.ammoFor(def.gun().caliber());
            if (!ammo.isEmpty()) {
                ItemDef a = ammo.get(rng.nextInt(ammo.size()));
                items.setAmmo(stack, rng.nextInt(def.gun().magazine() + 1), a.id());
            }
        }
        return stack;
    }

    /**
     * Picks a random lootable item of the given category and rarity, or of the nearest lower rarity
     * with stock; null when there is none. Never a rarer one: collectibles start at purple and keys
     * at blue, so walking up turned every white or green collectible roll into a ◈48,000 one and
     * every white key roll into a blue key. Never a jackpot either: those have their own chance.
     */
    public @Nullable ItemDef pick(Category category, Rarity rarity, Random rng) {
        Rarity[] all = Rarity.values();
        for (int index = rarity.ordinal(); index >= 0; index--) {
            List<ItemDef> candidates = new ArrayList<>();
            for (ItemDef def : registry.byCategory(category)) {
                if (def.lootable() && def.rarity() == all[index] && !jackpots.containsKey(def.id())) {
                    candidates.add(def);
                }
            }
            if (!candidates.isEmpty()) {
                return candidates.get(rng.nextInt(candidates.size()));
            }
        }
        return null;
    }

    private static Map<Rarity, Double> shifted(Map<Rarity, Double> base, int shift) {
        if (shift <= 0) {
            return base;
        }
        Map<Rarity, Double> out = new EnumMap<>(Rarity.class);
        for (Map.Entry<Rarity, Double> e : base.entrySet()) {
            out.put(e.getKey(), e.getValue() * Math.pow(1.0 + 0.55 * shift, e.getKey().ordinal()) / (1.0 + 0.4 * shift));
        }
        return out;
    }

    private static <T> @Nullable T weighted(Map<T, Double> weights, Random rng) {
        double total = 0;
        for (double w : weights.values()) {
            total += Math.max(0, w);
        }
        if (total <= 0) {
            return null;
        }
        double roll = rng.nextDouble() * total;
        for (Map.Entry<T, Double> e : weights.entrySet()) {
            roll -= Math.max(0, e.getValue());
            if (roll <= 0) {
                return e.getKey();
            }
        }
        return weights.keySet().iterator().next();
    }
}
