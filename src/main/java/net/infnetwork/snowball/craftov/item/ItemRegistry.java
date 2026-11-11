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
                    g.getDouble("move-speed", 0.95),
                    g.getString("mount", "PRIMARY".equalsIgnoreCase(g.getString("slot", "PRIMARY")) ? "rifle" : "pistol"),
                    g.getString("scope"));
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
                    Collections.unmodifiableSet(cures), Math.max(1, m.getInt("count", 1)),
                    MedSpec.Effect.valueOf(m.getString("effect", "NONE").toUpperCase(Locale.ROOT)),
                    m.getInt("effect-seconds", 0), m.getString("anim", "brush"), m.getString("sound", "med.bandage"));
        }
        net.infnetwork.snowball.craftov.weapon.OpticSpec optic = null;
        ConfigurationSection o = s.getConfigurationSection("optic");
        if (o != null) {
            optic = new net.infnetwork.snowball.craftov.weapon.OpticSpec(o.getDouble("fov", 0.75), o.getString("overlay"),
                    Set.copyOf(o.getStringList("mount").isEmpty() ? List.of("rifle") : o.getStringList("mount")));
        }
        net.infnetwork.snowball.craftov.weapon.MeleeSpec melee = null;
        ConfigurationSection me = s.getConfigurationSection("melee");
        if (me != null) {
            melee = new net.infnetwork.snowball.craftov.weapon.MeleeSpec(me.getInt("hits-normal", 2), me.getInt("hits-special", 3),
                    me.getInt("hits-boss", 4), me.getDouble("player-damage", 30), me.getInt("cooldown", 12), me.getDouble("reach", 3.0));
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
        int armorClass = s.getInt("class", 0);
        double defaultWeight = switch (category) {
            case WEAPON -> 3.5;
            case AMMO -> 0.012;
            case HELMET -> 0.8 + 0.35 * armorClass;
            case ARMOR -> 2.0 + 1.5 * armorClass;
            case RIG -> 0.8;
            case BACKPACK -> 1.2;
            case MEDICAL -> 0.25;
            case THROWABLE -> 0.5;
            case KEY -> 0.05;
            case COLLECTIBLE -> 1.0;
            case ATTACHMENT -> 0.5;
            case MELEE -> 0.3;
            default -> 0.6;
        };
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
                armorClass,
                durability,
                s.getDouble("speed-penalty", 0),
                s.getString("equipment"),
                s.getString("lock"),
                s.getBoolean("tradeable", true),
                s.getBoolean("lootable", true),
                s.getDouble("weight", defaultWeight),
                optic,
                melee);
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
