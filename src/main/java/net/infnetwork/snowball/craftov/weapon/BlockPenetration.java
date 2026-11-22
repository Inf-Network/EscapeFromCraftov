package net.infnetwork.snowball.craftov.weapon;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Which blocks a bullet goes through ({@code combat.penetration.blocks}): thin or soft ones such as
 * wooden doors, trapdoors, a single layer of planks, leaves and iron bars. Each needs at least its
 * {@code level} of the round's penetration (the ammo's {@code pen} in items.yml), uses that much up and
 * leaves the round {@code damage} of its damage, so a common rifle round (pen 3) goes through one door
 * or one plank wall but not two. Anything not listed stops a round; stone and tinted glass stay
 * bulletproof.
 *
 * <p>Keys are block ids, with {@code *} standing for any part of one ({@code *_glass_pane}), or a block
 * tag written {@code #wooden_doors}; the first key that matches a block counts. Without the section in
 * the server's config.yml the built-in table below (the same as the jar's config.yml) is used.
 */
final class BlockPenetration {

    /** What it takes a round to go through a block, and the share of its damage it keeps. */
    record Rule(int level, double damage) {
    }

    private static final Map<String, Rule> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("glass_pane", new Rule(1, 0.9));
        DEFAULTS.put("*_stained_glass_pane", new Rule(1, 0.9));
        DEFAULTS.put("iron_bars", new Rule(1, 0.85));
        DEFAULTS.put("#leaves", new Rule(1, 0.85));
        DEFAULTS.put("#wooden_trapdoors", new Rule(2, 0.75));
        DEFAULTS.put("#wooden_doors", new Rule(2, 0.7));
        DEFAULTS.put("#planks", new Rule(3, 0.6));
    }

    private final Logger logger;
    /** The configuration the table was built from: {@code /cv reload} swaps it for a new one. */
    private @Nullable FileConfiguration builtFrom;
    private Map<Material, Rule> table = Map.of();

    BlockPenetration(Logger logger) {
        this.logger = logger;
    }

    /** How a round goes through {@code type}, or null if it stops there. */
    @Nullable Rule of(FileConfiguration config, Material type) {
        if (config != builtFrom) {
            table = build(config);
            builtFrom = config;
        }
        return table.get(type);
    }

    private Map<Material, Rule> build(FileConfiguration config) {
        Map<String, Rule> rules = new LinkedHashMap<>();
        // get(path, null) skips the jar's defaults on purpose: getConfigurationSection would answer a
        // section the server's file lacks with an empty one, which would make every block bulletproof.
        if (config.get("combat.penetration.blocks", null) instanceof ConfigurationSection section) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection rule = section.getConfigurationSection(key);
                if (rule == null) {
                    logger.warning("combat.penetration.blocks." + key + " should be { level: <n>, damage: <share> }");
                    continue;
                }
                rules.put(key, new Rule(Math.max(0, rule.getInt("level", 1)), Math.max(0, Math.min(1, rule.getDouble("damage", 0.8)))));
            }
        } else {
            rules.putAll(DEFAULTS);
        }
        Map<Material, Rule> out = new EnumMap<>(Material.class);
        for (Map.Entry<String, Rule> entry : rules.entrySet()) {
            int matched = 0;
            for (Material type : matching(entry.getKey())) {
                if (out.putIfAbsent(type, entry.getValue()) == null) {
                    matched++;
                }
            }
            if (matched == 0) {
                logger.warning("combat.penetration.blocks: " + entry.getKey() + " matches no block");
            }
        }
        return out;
    }

    private static Iterable<Material> matching(String key) {
        String name = key.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("#")) {
            NamespacedKey id = NamespacedKey.fromString(name.substring(1));
            Tag<Material> tag = id == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, id, Material.class);
            return tag == null ? List.of() : tag.getValues();
        }
        String bare = name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
        StringBuilder regex = new StringBuilder();
        for (String part : bare.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        Pattern pattern = Pattern.compile(regex.toString());
        List<Material> out = new ArrayList<>();
        for (Material type : Material.values()) {
            if (!type.name().startsWith("LEGACY_") && type.isBlock() && pattern.matcher(type.name().toLowerCase(Locale.ROOT)).matches()) {
                out.add(type);
            }
        }
        return out;
    }
}
