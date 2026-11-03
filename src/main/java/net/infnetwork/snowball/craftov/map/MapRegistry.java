package net.infnetwork.snowball.craftov.map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/** Loads and saves maps/*.yml. */
public final class MapRegistry {

    private final File dir;
    private final Logger logger;
    private final Map<String, MapDef> maps = new LinkedHashMap<>();

    public MapRegistry(File dir, Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    public void load() {
        maps.clear();
        dir.mkdirs();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            return;
        }
        java.util.Arrays.sort(files);
        for (File file : files) {
            try {
                MapDef def = read(YamlConfiguration.loadConfiguration(file));
                maps.put(def.id(), def);
            } catch (RuntimeException ex) {
                logger.severe("Invalid map file " + file.getName() + ": " + ex.getMessage());
            }
        }
        logger.info("Loaded " + maps.size() + " raid maps");
    }

    public @Nullable MapDef get(String id) {
        return maps.get(id);
    }

    public Collection<MapDef> all() {
        return maps.values();
    }

    public void put(MapDef def) {
        maps.put(def.id(), def);
    }

    public File file(String id) {
        return new File(dir, id + ".yml");
    }

    // ------------------------------------------------------------------ reading

    private static MapDef read(YamlConfiguration y) {
        String id = y.getString("id");
        if (id == null) {
            throw new IllegalArgumentException("missing id");
        }
        ConfigurationSection bounds = y.getConfigurationSection("bounds");
        return new MapDef(
                id,
                y.getString("name", id),
                y.getString("description", ""),
                y.getString("template", "craftov_tpl_" + id),
                y.getInt("raid-minutes", 25) * 60,
                y.isConfigurationSection("camera") ? Pos.read(y.getConfigurationSection("camera")) : new Pos(0, 120, 0, 0),
                list(y, "spawns", m -> new MapDef.SpawnDef(intOf(m.get("group"), 1), Pos.read(m))),
                list(y, "extracts", m -> new MapDef.ExtractDef(
                        str(m.get("id"), "extract"), str(m.get("name"), "撤离点"), Pos.read(m),
                        Pos.num(or(m, "radius", 3)),
                        ExtractType.valueOf(str(m.get("type"), "ALWAYS").toUpperCase(Locale.ROOT)),
                        intOf(m.get("seconds"), 8), (long) Pos.num(or(m, "cost", 0)),
                        intOf(m.get("opens"), 0), intOf(m.get("closes"), 0), str(m.get("switch"), ""),
                        intOf(m.get("capacity"), 0), Pos.num(or(m, "chance", 1.0)))),
                list(y, "containers", m -> new MapDef.ContainerDef(str(m.get("type"), "crate"), Pos.read(m), intOf(m.get("tier"), 0))),
                list(y, "ai", m -> new MapDef.AiDef(Pos.read(m), intOf(m.get("count"), 1), Pos.num(or(m, "radius", 6)),
                        str(m.get("kind"), "patrol"))),
                list(y, "bosses", m -> new MapDef.BossDef(str(m.get("id"), "boss"), str(m.get("name"), "首领"), Pos.read(m),
                        intOf(m.get("guards"), 2), Pos.num(or(m, "chance", 1.0)), intOf(m.get("delay"), 120))),
                list(y, "doors", m -> new MapDef.DoorDef(str(m.get("lock"), "door"), Pos.read(m))),
                list(y, "switches", m -> new MapDef.SwitchDef(str(m.get("id"), "switch"), str(m.get("name"), "电闸"), Pos.read(m),
                        intOf(m.get("active"), 300))),
                bounds == null ? -10000 : bounds.getDouble("min-x"),
                bounds == null ? -10000 : bounds.getDouble("min-z"),
                bounds == null ? 10000 : bounds.getDouble("max-x"),
                bounds == null ? 10000 : bounds.getDouble("max-z"),
                y.getString("time", "day"));
    }

    private static <T> List<T> list(YamlConfiguration y, String key, Function<Map<?, ?>, T> mapper) {
        List<T> out = new ArrayList<>();
        for (Map<?, ?> m : y.getMapList(key)) {
            out.add(mapper.apply(m));
        }
        return List.copyOf(out);
    }

    private static Object or(Map<?, ?> m, String key, Object fallback) {
        Object value = m.get(key);
        return value == null ? fallback : value;
    }

    private static String str(Object o, String fallback) {
        return o == null ? fallback : o.toString();
    }

    private static int intOf(Object o, int fallback) {
        return o == null ? fallback : (int) Pos.num(o);
    }

    // ------------------------------------------------------------------ writing

    public void save(MapDef def) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.set("id", def.id());
        y.set("name", def.name());
        y.set("description", def.description());
        y.set("template", def.template());
        y.set("raid-minutes", def.raidSeconds() / 60);
        y.set("time", def.timeOfDay());
        y.set("camera", def.camera().write());
        Map<String, Object> bounds = new LinkedHashMap<>();
        bounds.put("min-x", def.minX());
        bounds.put("min-z", def.minZ());
        bounds.put("max-x", def.maxX());
        bounds.put("max-z", def.maxZ());
        y.set("bounds", bounds);
        y.set("spawns", def.spawns().stream().map(s -> {
            Map<String, Object> m = s.pos().write();
            m.put("group", s.group());
            return m;
        }).toList());
        y.set("extracts", def.extracts().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("name", e.name());
            m.putAll(e.pos().write());
            m.put("radius", e.radius());
            m.put("type", e.type().name());
            m.put("seconds", e.seconds());
            if (e.cost() > 0) m.put("cost", e.cost());
            if (e.opensAt() > 0) m.put("opens", e.opensAt());
            if (e.closesAt() > 0) m.put("closes", e.closesAt());
            if (!e.switchId().isEmpty()) m.put("switch", e.switchId());
            if (e.capacity() > 0) m.put("capacity", e.capacity());
            if (e.chance() < 1.0) m.put("chance", e.chance());
            return m;
        }).toList());
        y.set("containers", def.containers().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", c.type());
            m.putAll(c.pos().write());
            if (c.tier() > 0) m.put("tier", c.tier());
            return m;
        }).toList());
        y.set("ai", def.ai().stream().map(a -> {
            Map<String, Object> m = a.pos().write();
            m.put("count", a.count());
            m.put("radius", a.radius());
            m.put("kind", a.kind());
            return m;
        }).toList());
        y.set("bosses", def.bosses().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("name", b.name());
            m.putAll(b.pos().write());
            m.put("guards", b.guards());
            m.put("chance", b.chance());
            m.put("delay", b.delaySeconds());
            return m;
        }).toList());
        y.set("doors", def.doors().stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lock", d.lock());
            m.putAll(d.pos().write());
            return m;
        }).toList());
        y.set("switches", def.switches().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("name", s.name());
            m.putAll(s.pos().write());
            m.put("active", s.activeSeconds());
            return m;
        }).toList());
        dir.mkdirs();
        y.save(file(def.id()));
        maps.put(def.id(), def);
    }
}
