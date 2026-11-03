package net.infnetwork.snowball.craftov.item;

import net.infnetwork.snowball.craftov.combat.Injury;
import net.infnetwork.snowball.craftov.combat.MedSpec;
import net.infnetwork.snowball.craftov.weapon.AmmoSpec;
import net.infnetwork.snowball.craftov.weapon.FireMode;
import net.infnetwork.snowball.craftov.weapon.GunSpec;
import net.infnetwork.snowball.craftov.weapon.ThrowableSpec;
import net.infnetwork.snowball.craftov.weapon.WeaponSlot;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** All item definitions, keyed by id, in file order. */
public final class ItemRegistry {

    private final Map<String, ItemDef> items = new LinkedHashMap<>();
    private final Logger logger;

    public ItemRegistry(Logger logger) {
        this.logger = logger;
    }

    public void load(File file) {
        items.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("items");
        if (root == null) {
            logger.severe("items.yml has no 'items' section");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            try {
                items.put(id, parse(id, s));
            } catch (RuntimeException ex) {
                logger.severe("Invalid item '" + id + "': " + ex.getMessage());
            }
        }
        logger.info("Loaded " + items.size() + " item definitions");
    }

    private ItemDef parse(String id, ConfigurationSection s) {
        Category category = Category.valueOf(s.getString("category", "VALUABLE").toUpperCase(Locale.ROOT));
        Rarity rarity = Rarity.parse(s.getString("rarity", "WHITE"));
        List<String> lore = s.isList("lore") ? s.getStringList("lore")
                : s.isString("lore") ? List.of(s.getString("lore")) : List.of();
        GunSpec gun = null;
        ConfigurationSection g = s.getConfigurationSection("gun");
        if (g != null) {
            List<FireMode> modes = new ArrayList<>();
            for (String m : g.getStringList("modes")) {
                modes.add(FireMode.valueOf(m.toUpperCase(Locale.ROOT)));
            }
            if (modes.isEmpty()) {
                modes.add(FireMode.SEMI);
            }
            gun = new GunSpec(
                    g.getString("caliber", "9x19"),
                    WeaponSlot.valueOf(g.getString("slot", "PRIMARY").toUpperCase(Locale.ROOT)),
                    g.getInt("mag", 30),
                    g.getInt("rpm", 600),
                    List.copyOf(modes),
                    g.getDouble("damage", 30),
                    g.getDouble("range", 50),
                    g.getDouble("max-range", 160),
                    g.getDouble("hip-spread", 3.0),
                    g.getDouble("ads-spread", 0.5),
                    g.getDouble("recoil", 1.0),
                    g.getDouble("recoil-h", 0.5),
                    g.getInt("reload", 50),
                    g.getInt("zoom", 1),
                    g.getInt("pellets", 1),
                    g.getDouble("head", 2.0),
                    g.getString("sound", "craftov:gun.rifle"),
                    (float) g.getDouble("volume", 4.0),
                    g.getDouble("move-speed", 0.95));
        }
        AmmoSpec ammo = null;
        ConfigurationSection a = s.getConfigurationSection("ammo");
        if (a != null) {
            ammo = new AmmoSpec(a.getString("caliber", "9x19"), a.getInt("pen", 1),
                    a.getDouble("damage", 1.0), a.getDouble("armor-damage", 1.0));
        }
        MedSpec med = null;
        ConfigurationSection m = s.getConfigurationSection("med");
        if (m != null) {
            Set<Injury> cures = EnumSet.noneOf(Injury.class);
            for (String c : m.getStringList("cures")) {
                cures.add(Injury.valueOf(c.toUpperCase(Locale.ROOT)));
            }
            med = new MedSpec(m.getDouble("use", 2.0), m.getDouble("pool", 0), m.getDouble("per-use", 0),
                    Collections.unmodifiableSet(cures),
                    MedSpec.Effect.valueOf(m.getString("effect", "NONE").toUpperCase(Locale.ROOT)),
                    m.getInt("effect-seconds", 0));
        }
        ThrowableSpec throwable = null;
        ConfigurationSection t = s.getConfigurationSection("throwable");
        if (t != null) {
            throwable = new ThrowableSpec(ThrowableSpec.Type.valueOf(t.getString("type", "FRAG").toUpperCase(Locale.ROOT)),
                    t.getInt("fuse", 50), t.getDouble("radius", 5), t.getDouble("damage", 90));
        }
        int defaultStack = switch (category) {
            case AMMO -> 60;
            case VALUABLE, COLLECTIBLE -> 1;
            case MEDICAL, THROWABLE -> 3;
            default -> 1;
        };
        double durability = s.getDouble("durability", med != null ? med.pool() : 0);
        return new ItemDef(
                id,
                s.getString("name", id),
                category,
                rarity,
                s.getLong("value", 100),
                Math.max(1, Math.min(99, s.getInt("stack", defaultStack))),
                s.getString("model", "craftov:" + id),
                List.copyOf(lore),
                gun, ammo, med, throwable,
                s.getInt("slots", 0),
                s.getInt("class", 0),
                durability,
                s.getDouble("speed-penalty", 0),
                s.getString("equipment"),
                s.getString("lock"),
                s.getBoolean("tradeable", true),
                s.getBoolean("lootable", true));
    }

    public @Nullable ItemDef get(@Nullable String id) {
        return id == null ? null : items.get(id);
    }

    public Collection<ItemDef> all() {
        return Collections.unmodifiableCollection(items.values());
    }

    public List<ItemDef> byCategory(Category category) {
        List<ItemDef> out = new ArrayList<>();
        for (ItemDef def : items.values()) {
            if (def.category() == category) {
                out.add(def);
            }
        }
        return out;
    }

    public List<ItemDef> ammoFor(String caliber) {
        List<ItemDef> out = new ArrayList<>();
        for (ItemDef def : items.values()) {
            if (def.ammo() != null && def.ammo().caliber().equals(caliber)) {
                out.add(def);
            }
        }
        return out;
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(items.keySet());
    }
}
