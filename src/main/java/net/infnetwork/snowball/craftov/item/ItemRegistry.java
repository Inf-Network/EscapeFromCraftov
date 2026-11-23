package net.infnetwork.snowball.craftov.item;

import net.infnetwork.snowball.craftov.combat.Injury;
import net.infnetwork.snowball.craftov.combat.MedSpec;
import net.infnetwork.snowball.craftov.weapon.AmmoSpec;
import net.infnetwork.snowball.craftov.weapon.FireMode;
import net.infnetwork.snowball.craftov.weapon.GunSpec;
import net.infnetwork.snowball.craftov.weapon.Handling;
import net.infnetwork.snowball.craftov.weapon.OpticSpec;
import net.infnetwork.snowball.craftov.weapon.ThrowableSpec;
import net.infnetwork.snowball.craftov.weapon.WeaponSlot;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
    /**
     * What the jar's own items.yml gives for the keys a server's copy may lack. A server keeps the
     * items.yml it was first given (CraftovPlugin copies it only when missing), so one written before
     * {@code size} existed (revision 7 and older) would let every statue and generator into a single
     * cell, and one written before {@code handling} (revision 8 and older) would leave every gun alike:
     * an item whose entry lacks the key takes the jar's. A value written in the server's file always wins.
     * The same goes for a medical item's pose and cues: a file from before cues (#41) names one
     * {@code sound} and the vanilla poses (brush, crossbow, bundle, trident) that the item models' own
     * frames replaced, which would now play on top of them, so a medical item whose entry has no
     * {@code cues} takes the jar's pose and cues together. And for a gun's aimed kick: a file from
     * before {@code ads-recoil} and {@code ads-recoil-h} (revision 10 and older) would give every gun the
     * same one, so a gun whose entry lacks either takes the jar's.
     */
    private final Bundled bundled;

    /**
     * The jar's {@code size} by item id, its {@code handling} by gun, sight or part id, its pose and
     * cues by medical item id (those with cues), and its {@code ads-recoil} and {@code ads-recoil-h} by
     * gun id.
     */
    public record Bundled(Map<String, Integer> sizes, Map<String, Double> handling, Map<String, MedUse> meds,
                          Map<String, Double> adsRecoil, Map<String, Double> adsRecoilH) {
        public Bundled {
            sizes = Map.copyOf(sizes);
            handling = Map.copyOf(handling);
            meds = Map.copyOf(meds);
            adsRecoil = Map.copyOf(adsRecoil);
            adsRecoilH = Map.copyOf(adsRecoilH);
        }
    }

    /** A medical item's vanilla pose ({@code anim}) and sounds ({@code cues}) as an items.yml gives them. */
    public record MedUse(String animation, List<MedSpec.Cue> cues) {
    }

    /** The sections of an item that carry a handling: a gun's own, a sight's or a part's change to it. */
    private static final List<String> HANDLED = List.of("gun", "optic", "attach");

    public ItemRegistry(Logger logger, Bundled bundled) {
        this.logger = logger;
        this.bundled = bundled;
    }

    /**
     * The {@code size}, {@code handling}, medical pose and cues and aimed kick of every item in an
     * items.yml (the jar's copy), or none if it cannot be read.
     */
    public static Bundled bundled(@Nullable InputStream in) {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        Map<String, Double> handling = new LinkedHashMap<>();
        Map<String, MedUse> meds = new LinkedHashMap<>();
        Map<String, Double> adsRecoil = new LinkedHashMap<>();
        Map<String, Double> adsRecoilH = new LinkedHashMap<>();
        if (in == null) {
            return new Bundled(sizes, handling, meds, adsRecoil, adsRecoilH);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try (in) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException ex) {
            return new Bundled(sizes, handling, meds, adsRecoil, adsRecoilH);
        }
        ConfigurationSection root = yaml.getConfigurationSection("items");
        if (root == null) {
            return new Bundled(sizes, handling, meds, adsRecoil, adsRecoilH);
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            if (s.contains("size")) {
                sizes.put(id, Math.max(1, s.getInt("size", 1)));
            }
            for (String part : HANDLED) {
                if (s.contains(part + ".handling")) {
                    handling.put(id, s.getDouble(part + ".handling"));
                }
            }
            ConfigurationSection m = s.getConfigurationSection("med");
            if (m != null && m.contains("cues")) {
                meds.put(id, new MedUse(m.getString("anim", "none"), medCues(m)));
            }
            if (s.contains("gun.ads-recoil")) {
                adsRecoil.put(id, s.getDouble("gun.ads-recoil"));
            }
            if (s.contains("gun.ads-recoil-h")) {
                adsRecoilH.put(id, s.getDouble("gun.ads-recoil-h"));
            }
        }
        return new Bundled(sizes, handling, meds, adsRecoil, adsRecoilH);
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
        List<String> borrowed = new ArrayList<>();
        List<String> borrowedHandling = new ArrayList<>();
        List<String> borrowedMeds = new ArrayList<>();
        List<String> borrowedKick = new ArrayList<>();
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
            if (!s.contains("size") && bundled.sizes().getOrDefault(id, 1) > 1) {
                borrowed.add(id);
            }
            for (String part : HANDLED) {
                if (s.isConfigurationSection(part) && !s.contains(part + ".handling") && bundled.handling().containsKey(id)) {
                    borrowedHandling.add(id);
                }
            }
            if (s.isConfigurationSection("med") && !s.contains("med.cues") && bundled.meds().containsKey(id)) {
                borrowedMeds.add(id);
            }
            if (s.isConfigurationSection("gun") && (!s.contains("gun.ads-recoil") && bundled.adsRecoil().containsKey(id)
                    || !s.contains("gun.ads-recoil-h") && bundled.adsRecoilH().containsKey(id))) {
                borrowedKick.add(id);
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
        if (!borrowed.isEmpty()) {
            logger.warning(file.getName() + " gives no secure-container size for " + borrowed.size() + " items ("
                    + String.join(", ", borrowed) + "): they take the jar's sizes. Replace the file with the jar's"
                    + " copy, or add size: to each, to change them.");
        }
        if (!borrowedHandling.isEmpty()) {
            logger.warning(file.getName() + " gives no handling for " + borrowedHandling.size() + " guns and parts ("
                    + String.join(", ", borrowedHandling) + "): they take the jar's. Replace the file with the jar's"
                    + " copy, or add handling: to each, to change them.");
        }
        if (!borrowedMeds.isEmpty()) {
            logger.warning(file.getName() + " gives no cues for " + borrowedMeds.size() + " medical items ("
                    + String.join(", ", borrowedMeds) + "): they are used with the jar's sounds and pose. Replace the"
                    + " file with the jar's copy, or add cues: to each, to change them.");
        }
        if (!borrowedKick.isEmpty()) {
            logger.warning(file.getName() + " gives no aimed kick for " + borrowedKick.size() + " guns ("
                    + String.join(", ", borrowedKick) + "): they take the jar's ads-recoil and ads-recoil-h. Replace"
                    + " the file with the jar's copy, or add them to each, to change them.");
        }
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
                    Math.max(0, g.getDouble("move", defaultMove(g.getString("action", ""), modes,
                            "SECONDARY".equalsIgnoreCase(g.getString("slot", "PRIMARY"))))),
                    g.getDouble("recoil", 1.0),
                    g.getDouble("recoil-h", 0.5),
                    Math.max(0, Math.min(1, g.getDouble("recoil-return", 0.7))),
                    g.getInt("reload", 50),
                    adsClass(id, g),
                    g.getInt("pellets", 1),
                    g.getDouble("head", 2.0),
                    (float) g.getDouble("volume", 4.0),
                    g.getDouble("move-speed", 0.95),
                    g.getString("mount", "PRIMARY".equalsIgnoreCase(g.getString("slot", "PRIMARY")) ? "rifle" : "pistol"),
                    g.getString("scope"),
                    g.getString("audio", id),
                    g.getString("action", modes.contains(FireMode.PUMP) ? "pump" : modes.contains(FireMode.BOLT) ? "bolt"
                            : "SECONDARY".equalsIgnoreCase(g.getString("slot", "PRIMARY")) ? "pistol" : "ar"),
                    Handling.clamp(g.getDouble("handling", bundled.handling().getOrDefault(id, Handling.fallback(adsClass(id, g))))),
                    share(g, "ads-recoil", bundled.adsRecoil().get(id)),
                    share(g, "ads-recoil-h", bundled.adsRecoilH().get(id)));
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
            MedUse use = m.contains("cues") ? null : bundled.meds().get(id);
            if (use == null) {
                use = new MedUse(m.getString("anim", "none"), medCues(m));
            }
            Set<Injury> cures = EnumSet.noneOf(Injury.class);
            for (String c : m.getStringList("cures")) {
                cures.add(Injury.valueOf(c.toUpperCase(Locale.ROOT)));
            }
            med = new MedSpec(m.getDouble("use", 2.0), m.getDouble("pool", 0), m.getDouble("per-use", 0),
                    Collections.unmodifiableSet(cures), Math.max(1, m.getInt("count", 1)), m.getBoolean("one-part", false),
                    MedSpec.Effect.valueOf(m.getString("effect", "NONE").toUpperCase(Locale.ROOT)),
                    m.getInt("effect-seconds", 0), use.animation(), use.cues());
        }
        OpticSpec optic = null;
        ConfigurationSection o = s.getConfigurationSection("optic");
        if (o != null) {
            double power;
            if (o.contains("magnification")) {
                power = Math.max(1.0, o.getDouble("magnification"));
            } else {
                // An items.yml from before optics gave their true power still has the old field of view.
                power = OpticSpec.legacyPower(o.getDouble("fov", 0.75));
                logger.warning("items.yml: " + id + " still has optic fov instead of magnification: it zooms "
                        + OpticSpec.label(power) + "x as before. Back up items.yml,"
                        + " replace it with the one in the plugin jar and restart.");
            }
            OpticSpec.Tier tier = new OpticSpec(power, null, Set.of(), 0).tier();
            optic = new OpticSpec(power, o.getString("overlay"),
                    Set.copyOf(o.getStringList("mount").isEmpty() ? List.of("rifle") : o.getStringList("mount")),
                    o.getDouble("handling", bundled.handling().getOrDefault(id, Handling.opticFallback(tier))));
        }
        net.infnetwork.snowball.craftov.weapon.MeleeSpec melee = null;
        ConfigurationSection me = s.getConfigurationSection("melee");
        if (me != null) {
            // An items.yml from before knives went through armour has player-damage instead of damage.
            // It still loads, but the knife cuts at pen 1, keeps its old lore and stays tradeable.
            if (!me.contains("damage") && (me.contains("player-damage") || me.contains("hits-normal"))) {
                logger.warning("items.yml: " + id + " still has the old melee keys (hits-normal, player-damage): knives now"
                        + " go through armour with damage, pen and armor-damage, so it cuts at pen 1 and keeps its old lore"
                        + " and trade settings. Back up items.yml, replace it with the one in the plugin jar and restart.");
            }
            melee = new net.infnetwork.snowball.craftov.weapon.MeleeSpec(me.getDouble("damage", me.getDouble("player-damage", 34)),
                    Math.max(0, me.getInt("pen", 1)), Math.max(0, me.getDouble("armor-damage", 1.0)), me.getDouble("head", 1.0),
                    Math.max(1, me.getInt("cooldown", 12)), me.getDouble("reach", 3.0));
        }
        net.infnetwork.snowball.craftov.weapon.AttachSpec attach = null;
        ConfigurationSection at = s.getConfigurationSection("attach");
        if (at != null) {
            attach = new net.infnetwork.snowball.craftov.weapon.AttachSpec(at.getString("slot", "muzzle"),
                    Set.copyOf(at.getStringList("mount").isEmpty() ? List.of("rifle") : at.getStringList("mount")),
                    at.getDouble("recoil", 1), at.getDouble("recoil-h", 1), at.getDouble("hip-spread", 1), at.getDouble("ads-spread", 1),
                    at.getDouble("reload", 1), at.getDouble("move-speed", 1), at.getDouble("magazine", 1),
                    at.getBoolean("suppressed", false), at.getString("light"),
                    at.getDouble("handling", bundled.handling().getOrDefault(id, 0.0)));
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
        int maxStack = Math.max(1, Math.min(99, s.getInt("stack", defaultStack)));
        // Ammo shares a secure-container cell 30 rounds at a time: a whole box of 60 per cell made
        // the container a free ammo store.
        int secureStack = Math.max(1, Math.min(maxStack, s.getInt("secure-stack", category == Category.AMMO ? 30 : maxStack)));
        return new ItemDef(
                id,
                s.getString("name", id),
                category,
                rarity,
                s.getLong("value", 100),
                maxStack,
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
                attach,
                Math.max(1, s.contains("size") ? s.getInt("size", 1) : bundled.sizes().getOrDefault(id, 1)),
                secureStack);
    }

    /**
     * A share (0..2) the section gives under {@code key}, else the jar's for the same gun ({@code jar}),
     * else NaN: a gun neither gives one for takes config.yml's (weapons.recoil.ads, ads-h).
     */
    private static double share(ConfigurationSection section, String key, @Nullable Double jar) {
        double value = section.contains(key) ? section.getDouble(key) : jar != null ? jar : Double.NaN;
        return Double.isNaN(value) ? Double.NaN : Math.max(0, Math.min(2, value));
    }

    /**
     * The gun's ads-class (GunSpec.ADS_CLASSES): items.yml's ads-class, or, for an items.yml from
     * before it existed (which had a zoom number instead), the one its action implies.
     */
    private String adsClass(String id, ConfigurationSection g) {
        String action = g.getString("action", "");
        String wanted = g.getString("ads-class", GunSpec.defaultAdsClass(action)).toLowerCase(Locale.ROOT);
        if (!GunSpec.ADS_CLASSES.contains(wanted)) {
            logger.warning("items.yml: " + id + " has an unknown ads-class '" + wanted + "'; it aims like a rifle.");
            return "rifle";
        }
        return wanted;
    }

    /**
     * How far a gun's cone widens at full walking pace when items.yml does not say (`move`), by its
     * action: a server keeps the items.yml it was first given, so guns listed before `move` existed
     * still get one. Handguns and the pump gun least, rifles more, the machine gun and the long guns most.
     */
    static double defaultMove(String action, List<FireMode> modes, boolean secondary) {
        String kind = !action.isEmpty() ? action : modes.contains(FireMode.PUMP) ? "pump" : modes.contains(FireMode.BOLT) ? "bolt"
                : secondary ? "pistol" : "ar";
        return switch (kind) {
            case "pistol", "pump" -> 1.5;
            case "pistol_heavy", "smg", "pdw" -> 2.0;
            case "ak" -> 3.5;
            case "lmg" -> 5.0;
            case "dmr" -> 4.5;
            case "bolt" -> 6.0;
            default -> 3.0;
        };
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

    /**
     * A medical item's sounds, items.yml {@code cues: [[0, med.tear], [0.3, med.wrap] ...]}: share of
     * the use time and sound, sorted by time. A medical item with no cues that the jar has none for
     * either (one a server added to its own file) may name one {@code sound}, played as the use starts.
     */
    private static List<MedSpec.Cue> medCues(ConfigurationSection m) {
        List<MedSpec.Cue> cues = new ArrayList<>();
        for (Object entry : m.getList("cues", List.of())) {
            if (entry instanceof List<?> pair && pair.size() == 2 && pair.get(0) instanceof Number at && pair.get(1) != null) {
                cues.add(new MedSpec.Cue(Math.max(0, Math.min(1, at.doubleValue())), String.valueOf(pair.get(1))));
            }
        }
        if (cues.isEmpty()) {
            cues.add(new MedSpec.Cue(0, m.getString("sound", "med.bandage")));
        }
        cues.sort(java.util.Comparator.comparingDouble(MedSpec.Cue::at));
        return List.copyOf(cues);
    }
}
