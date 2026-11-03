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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/** Rolls container contents from loot.yml. */
public final class LootService {

    private final ItemRegistry registry;
    private final ItemFactory items;
    private final Logger logger;
    private final Map<String, ContainerType> types = new LinkedHashMap<>();

    public LootService(ItemFactory items, Logger logger) {
        this.items = items;
        this.registry = items.registry();
        this.logger = logger;
    }

    public void load(File file) {
        types.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
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
            List<Integer> range = s.getIntegerList("items");
            types.put(id, new ContainerType(id, s.getString("name", id), s.getString("model", "craftov:container/" + id),
                    (float) s.getDouble("scale", 1.0), s.getDouble("hitbox-width", 0.9), s.getDouble("hitbox-height", 0.9),
                    Math.max(1, Math.min(6, s.getInt("rows", 3))),
                    range.isEmpty() ? 2 : range.get(0), range.size() < 2 ? 4 : range.get(1),
                    s.getDouble("search-speed", 1.0), categories, rarities));
        }
        logger.info("Loaded " + types.size() + " container types");
    }

    public @Nullable ContainerType type(String id) {
        return types.get(id);
    }

    public Collection<ContainerType> types() {
        return types.values();
    }

    /**
     * Rolls items for a container.
     *
     * @param shift quality shift: map difficulty plus container hotness (0 = baseline)
     */
    public List<ItemStack> roll(ContainerType type, int shift) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int count = type.minItems() + rng.nextInt(Math.max(1, type.maxItems() - type.minItems() + 1));
        count += shift > 1 ? rng.nextInt(2) : 0;
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Category category = weighted(type.categories(), rng);
            Rarity rarity = weighted(shifted(type.rarities(), shift), rng);
            if (category == null || rarity == null) {
                continue;
            }
            ItemDef def = pick(category, rarity, rng);
            if (def == null) {
                continue;
            }
            out.add(stackFor(def, rng));
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

    /** Picks a random lootable item of the given category, walking to the nearest rarity with stock. */
    public @Nullable ItemDef pick(Category category, Rarity rarity, Random rng) {
        Rarity[] all = Rarity.values();
        for (int distance = 0; distance < all.length; distance++) {
            for (int dir : new int[]{-1, 1}) {
                int index = rarity.ordinal() + dir * distance;
                if (index < 0 || index >= all.length || (distance == 0 && dir == 1)) {
                    continue;
                }
                List<ItemDef> candidates = new ArrayList<>();
                for (ItemDef def : registry.byCategory(category)) {
                    if (def.lootable() && def.rarity() == all[index]) {
                        candidates.add(def);
                    }
                }
                if (!candidates.isEmpty()) {
                    return candidates.get(rng.nextInt(candidates.size()));
                }
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
