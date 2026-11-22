package net.infnetwork.snowball.craftov.item;

import net.infnetwork.snowball.craftov.combat.Injury;
import net.infnetwork.snowball.craftov.combat.MedSpec;
import net.infnetwork.snowball.craftov.weapon.AmmoSpec;
import net.infnetwork.snowball.craftov.weapon.FireMode;
import net.infnetwork.snowball.craftov.weapon.GunSpec;
import net.infnetwork.snowball.craftov.weapon.ThrowableSpec;
import net.infnetwork.snowball.craftov.weapon.WeaponSlot;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    /**
     * Reads items.yml into a fresh table and only swaps it in when the whole file is good. A syntax
     * error, a missing {@code items} section or one broken item keeps the table that is loaded now:
     * an empty or partial table would make every loadout drop the items it no longer knows.
     * Returns null on success, otherwise what is wrong (Chinese, shown to whoever reloaded).
     */
    public @Nullable String load(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(read(file));
        } catch (IOException ex) {
            return fail("无法读取 " + file.getName() + "：" + ex.getMessage());
        } catch (InvalidConfigurationException ex) {
            return fail(file.getName() + " 不是有效的 YAML：" + ex.getMessage());
        }
        ConfigurationSection root = yaml.getConfigurationSection("items");
        if (root == null) {
            return fail(file.getName() + " 里没有 items 段");
        }
        Map<String, ItemDef> parsed = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                errors.add(id + "：不是一个物品段");
                continue;
            }
            try {
                parsed.put(id, parse(id, s));
            } catch (RuntimeException ex) {
                errors.add(id + "：" + ex.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            return fail(file.getName() + " 中有 " + errors.size() + " 个物品无效：" + String.join("；", errors));
        }
        if (parsed.isEmpty()) {
            return fail(file.getName() + " 里没有任何物品");
        }
        items.clear();
        items.putAll(parsed);
        logger.info("Loaded " + items.size() + " item definitions");
        return null;
    }

    /**
     * The file as text. A file saved in another encoding (GBK from Notepad, say) is read the way
     * Bukkit always read it, with the bytes that are not UTF-8 replaced: the ids still load, only
     * the names show garbage until the file is saved as UTF-8.
     */
    private String read(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            logger.warning(file.getName() + " is not valid UTF-8: the characters that could not be read are replaced. Save it as UTF-8.");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private String fail(String reason) {
        logger.severe("items.yml not loaded, keeping the " + items.size() + " item definitions already loaded: " + reason);
        return reason;
    }

    public boolean isEmpty() {
        return items.isEmpty();
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
                    g.getString("scope"),
                    g.getString("audio", id),
                    g.getString("action", modes.contains(FireMode.PUMP) ? "pump" : modes.contains(FireMode.BOLT) ? "bolt"
                            : "SECONDARY".equalsIgnoreCase(g.getString("slot", "PRIMARY")) ? "pistol" : "ar"));
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
        net.infnetwork.snowball.craftov.weapon.AttachSpec attach = null;
        ConfigurationSection at = s.getConfigurationSection("attach");
        if (at != null) {
            attach = new net.infnetwork.snowball.craftov.weapon.AttachSpec(at.getString("slot", "muzzle"),
                    Set.copyOf(at.getStringList("mount").isEmpty() ? List.of("rifle") : at.getStringList("mount")),
                    at.getDouble("recoil", 1), at.getDouble("recoil-h", 1), at.getDouble("hip-spread", 1), at.getDouble("ads-spread", 1),
                    at.getDouble("reload", 1), at.getDouble("move-speed", 1), at.getDouble("magazine", 1),
                    at.getBoolean("suppressed", false), at.getString("light"));
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
                melee,
                attach);
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
