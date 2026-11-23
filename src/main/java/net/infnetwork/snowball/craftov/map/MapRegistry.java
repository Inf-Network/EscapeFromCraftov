package net.infnetwork.snowball.craftov.map;

import net.infnetwork.snowball.craftov.map.gen.ProvingGroundGenerator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Loads and saves maps/*.yml. A file that does not parse is reported and left alone (see
 * {@link #broken()}), never replaced by a default.
 *
 * <p>Drafts: a map the editor has made but nobody has published is an ordinary file with
 * {@code enabled: false}, loaded but kept out of matchmaking. Changes to a published map are saved
 * apart, in maps/drafts/&lt;id&gt;.yml, so the live file is only replaced when they are published.
 */
public final class MapRegistry {

    private final File dir;
    private final File drafts;
    private final Logger logger;
    private final Map<String, MapDef> maps = new LinkedHashMap<>();
    private final Set<String> broken = new LinkedHashSet<>();

    public MapRegistry(File dir, Logger logger) {
        this.dir = dir;
        this.drafts = new File(dir, "drafts");
        this.logger = logger;
    }

    public void load() {
        maps.clear();
        broken.clear();
        dir.mkdirs();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            return;
        }
        java.util.Arrays.sort(files);
        for (File file : files) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file);
                MapDef def = read(yaml);
                maps.put(def.id(), def);
                warnReferences(def);
            } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
                reportBroken(file, ex);
            }
        }
        logger.info("Loaded " + maps.size() + " raid maps");
    }

    /**
     * Logs lever extracts that can never open, switches hidden under another on the same block, and
     * ids used twice. A lever extract whose switch is missing tells players to pull a lever that does
     * not exist; two levers sharing an id open each other's extracts, since a raid keys pulled levers
     * by id.
     */
    private void warnReferences(MapDef def) {
        String map = def.name() + " (" + def.id() + ")";
        Map<MapDef.SwitchDef, MapDef.SwitchDef> hidden = hiddenSwitches(def);
        hidden.forEach((sw, shown) -> logger.warning("Map " + map + ": switch " + sw.id() + " is on the same block as " + shown.id()
                + " (" + blockKey(sw.pos()) + "), and raids only find " + shown.id()));
        Set<String> hiddenIds = new LinkedHashSet<>(hidden.keySet().stream().map(MapDef.SwitchDef::id).toList());
        for (MapDef.ExtractDef extract : unboundSwitchExtracts(def)) {
            logger.warning("Map " + map + ": lever extract " + extract.id() + (extract.switchId().isEmpty() ? " is bound to no switch"
                    : hiddenIds.contains(extract.switchId()) ? " needs switch " + extract.switchId() + ", which another switch on its block hides"
                    : " needs switch " + extract.switchId() + ", which the map does not have")
                    + ", so it never opens (raids show it as unavailable)");
        }
        for (String id : duplicates(def.extracts().stream().map(MapDef.ExtractDef::id).toList())) {
            logger.warning("Map " + map + ": more than one extract has the id " + id);
        }
        for (String id : duplicates(def.switches().stream().map(MapDef.SwitchDef::id).toList())) {
            logger.warning("Map " + map + ": more than one switch has the id " + id + " (pulling one opens the extracts of all)");
        }
        for (String id : duplicates(def.bosses().stream().map(MapDef.BossDef::id).toList())) {
            logger.warning("Map " + map + ": more than one boss has the id " + id);
        }
    }

    /**
     * Lever extracts bound to no switch, to one the map does not have, or to one hidden under
     * another switch on the same block (see {@link #hiddenSwitches}): they never open.
     */
    public static List<MapDef.ExtractDef> unboundSwitchExtracts(MapDef def) {
        Set<String> reachable = new LinkedHashSet<>(switchOnBlock(def).values().stream().map(MapDef.SwitchDef::id).toList());
        return def.extracts().stream()
                .filter(e -> e.type() == ExtractType.SWITCH && !reachable.contains(e.switchId()))
                .toList();
    }

    /**
     * Switches a raid never finds: a raid keys levers by block, and of two switches on one block
     * only the later one in the file is pulled. Each hidden switch maps to the one hiding it.
     */
    public static Map<MapDef.SwitchDef, MapDef.SwitchDef> hiddenSwitches(MapDef def) {
        Map<String, MapDef.SwitchDef> top = switchOnBlock(def);
        Map<MapDef.SwitchDef, MapDef.SwitchDef> out = new LinkedHashMap<>();
        for (MapDef.SwitchDef sw : def.switches()) {
            MapDef.SwitchDef shown = top.get(blockKey(sw.pos()));
            // Two markers with one id on one block hide nothing (the id is reported as used twice).
            if (shown != sw && !shown.id().equals(sw.id())) {
                out.put(sw, shown);
            }
        }
        return out;
    }

    /** The switch a raid finds on each block, as RaidManager fills Raid.switchBlocks: the last one wins. */
    private static Map<String, MapDef.SwitchDef> switchOnBlock(MapDef def) {
        Map<String, MapDef.SwitchDef> top = new LinkedHashMap<>();
        def.switches().forEach(sw -> top.put(blockKey(sw.pos()), sw));
        return top;
    }

    /** Whether a marker at {@code pos} is on the block at x, y, z (the block a raid looks it up by). */
    public static boolean sameBlock(Pos pos, int x, int y, int z) {
        return (int) Math.floor(pos.x()) == x && (int) Math.floor(pos.y()) == y && (int) Math.floor(pos.z()) == z;
    }

    private static String blockKey(Pos pos) {
        return (int) Math.floor(pos.x()) + "," + (int) Math.floor(pos.y()) + "," + (int) Math.floor(pos.z());
    }

    /** Ids that appear more than once, each once, in order of first appearance. */
    public static Set<String> duplicates(List<String> ids) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> twice = new LinkedHashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                twice.add(id);
            }
        }
        return twice;
    }

    /**
     * A map file that does not parse is left as it is and copied to {@code <name>.broken}; its id
     * (the file name) is remembered so a built-in map is not regenerated over the file.
     */
    private void reportBroken(File file, Exception ex) {
        String id = file.getName().substring(0, file.getName().length() - ".yml".length());
        broken.add(id);
        String copy = file.getName() + ".broken";
        try {
            Files.copy(file.toPath(), new File(dir, copy).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException copyFailed) {
            copy = "(copy failed: " + copyFailed.getMessage() + ")";
        }
        String reason = String.valueOf(ex.getMessage()).strip().replace('\n', ' ');
        logger.severe("==================================================================");
        logger.severe("Map file maps/" + file.getName() + " is invalid and was NOT loaded: " + reason);
        logger.severe("The file is left unchanged (a copy is kept as " + copy + "), and the map is not regenerated.");
        logger.severe("Fix it and run /cv reload, or delete it to get the default map back on the next start.");
        logger.severe("==================================================================");
    }

    /** Ids (file names) of map files that exist but could not be read on the last load. */
    public Set<String> broken() {
        return Collections.unmodifiableSet(broken);
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

    /**
     * maps/&lt;id&gt;.yml. An id that would put the file anywhere else ("../config" is
     * plugins/Craftov/config.yml) is refused with an IllegalArgumentException.
     */
    public File file(String id) {
        return inside(dir, id + ".yml");
    }

    /** maps/drafts/&lt;id&gt;.yml: unpublished changes to a published map. Ids are checked as in {@link #file}. */
    public File draftFile(String id) {
        return inside(drafts, id + ".yml");
    }

    private static File inside(File folder, String name) {
        Path base = folder.toPath().toAbsolutePath().normalize();
        Path file = base.resolve(name).normalize(); // InvalidPathException is an IllegalArgumentException too
        if (!base.equals(file.getParent())) {
            throw new IllegalArgumentException("map file \"" + name + "\" would be written outside " + base);
        }
        return file.toFile();
    }

    // ------------------------------------------------------------------ drafts

    /** The saved draft of a published map, or null if there is none. Throws if the file does not parse. */
    public @Nullable MapDef loadDraft(String id) throws IOException {
        File file;
        try {
            file = draftFile(id);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (!file.isFile()) {
            return null;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file);
            return read(yaml).withEnabled(false);
        } catch (InvalidConfigurationException | RuntimeException ex) {
            throw new IOException("maps/drafts/" + file.getName() + ": " + String.valueOf(ex.getMessage()).strip().replace('\n', ' '), ex);
        }
    }

    public boolean hasDraft(String id) {
        try {
            return draftFile(id).isFile();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** Saves changes to a published map without touching the live file or what matchmaking uses. */
    public void saveDraft(MapDef def) throws IOException {
        File target;
        try {
            target = draftFile(def.id());
        } catch (IllegalArgumentException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        drafts.mkdirs();
        write(def.withEnabled(false), target);
    }

    public void deleteDraft(String id) {
        try {
            Files.deleteIfExists(draftFile(id).toPath());
        } catch (IOException | IllegalArgumentException ex) {
            logger.warning("Could not delete the draft of map " + id + ": " + ex.getMessage());
        }
    }

    /**
     * Keeps the live file as maps/&lt;id&gt;.yml.prev before a publish replaces it (one generation:
     * enough to go back by hand when a publish was a mistake).
     */
    public void keepPrevious(String id) {
        try {
            File live = file(id);
            if (live.isFile()) {
                Files.copy(live.toPath(), new File(dir, live.getName() + ".prev").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | IllegalArgumentException ex) {
            logger.warning("Could not keep a copy of maps/" + id + ".yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ checks

    /** Fewest loot containers a published map has. */
    public static final int MIN_CONTAINERS = 5;

    /**
     * Why this map may not be published, in Chinese for the editor; empty when it may. A published
     * map has to work for a full raid: an exit that is always there, a spawn group for each squad a
     * raid takes ({@code squads}, match.max-squads), something to loot, and lever extracts that can
     * open. Without spawns every squad lands on one block at the world spawn; without an exit the
     * whole raid goes missing when time runs out.
     */
    public static List<String> publishProblems(MapDef def, int squads) {
        List<String> out = new ArrayList<>();
        if (def.extracts().stream().noneMatch(e -> e.type() == ExtractType.ALWAYS && e.chance() >= 1.0)) {
            out.add("没有每局都开放的常驻撤离点（类型“常驻撤离”、出现概率 1）");
        }
        long groups = def.spawns().stream().map(MapDef.SpawnDef::group).distinct().count();
        if (groups < squads) {
            out.add("出生点只有 " + groups + " 组，至少要 " + squads + " 组（每支小队一组，match.max-squads）");
        }
        if (def.containers().size() < MIN_CONTAINERS) {
            out.add("物资箱只有 " + def.containers().size() + " 个，至少要 " + MIN_CONTAINERS + " 个");
        }
        Map<MapDef.SwitchDef, MapDef.SwitchDef> hidden = hiddenSwitches(def);
        hidden.forEach((sw, shown) -> out.add("电闸 " + sw.id() + " 和 " + shown.id() + " 在同一个拉杆上，对局里只有 " + shown.id()
                + " 拉得动（用删除工具删掉多余的那个）"));
        Set<String> hiddenIds = new LinkedHashSet<>(hidden.keySet().stream().map(MapDef.SwitchDef::id).toList());
        for (MapDef.ExtractDef extract : unboundSwitchExtracts(def)) {
            out.add("需拉闸撤离点 " + extract.name() + (extract.switchId().isEmpty() ? " 没有绑定电闸"
                    : hiddenIds.contains(extract.switchId()) ? " 绑定的电闸 " + extract.switchId() + " 拉不到"
                    : " 绑定的电闸 " + extract.switchId() + " 不存在"));
        }
        duplicates(def.extracts().stream().map(MapDef.ExtractDef::id).toList()).forEach(id -> out.add("撤离点编号 " + id + " 重复"));
        duplicates(def.switches().stream().map(MapDef.SwitchDef::id).toList()).forEach(id -> out.add("电闸编号 " + id + " 重复"));
        duplicates(def.bosses().stream().map(MapDef.BossDef::id).toList()).forEach(id -> out.add("首领编号 " + id + " 重复"));
        return out;
    }

    /** Soldier and boss posts closer than this (flat) to a player spawn point are reported. */
    public static final double SPAWN_CLEARANCE = 30;

    /** A soldier or boss post too close to a player spawn point. */
    public record CloseSpawn(String kind, Pos post, int group, double distance) {
    }

    /**
     * Soldier and boss posts within {@link #SPAWN_CLEARANCE} blocks of a player spawn point, the
     * nearest spawn for each: a squad landing there is in a fight before it can take a step.
     */
    public static List<CloseSpawn> closeSpawns(MapDef def) {
        List<CloseSpawn> out = new ArrayList<>();
        Map<Pos, String> posts = new LinkedHashMap<>();
        def.ai().forEach(ai -> posts.putIfAbsent(ai.pos(), ai.kind()));
        def.bosses().forEach(boss -> posts.putIfAbsent(boss.pos(), "boss"));
        posts.forEach((post, kind) -> {
            MapDef.SpawnDef nearest = null;
            double best = Double.MAX_VALUE;
            for (MapDef.SpawnDef spawn : def.spawns()) {
                double d = Math.hypot(spawn.pos().x() - post.x(), spawn.pos().z() - post.z());
                if (d < best) {
                    best = d;
                    nearest = spawn;
                }
            }
            if (nearest != null && best < SPAWN_CLEARANCE) {
                out.add(new CloseSpawn(kind, post, nearest.group(), best));
            }
        });
        return out;
    }

    /**
     * Logs the live maps that {@link #publishProblems} would refuse today: files from before drafts
     * existed have no {@code enabled} key and count as published however unfinished they are (a
     * map with no spawns lands every squad on the world spawn; one without an always-open exit
     * loses everybody when time runs out). They stay live, so the admin is told which to fix in the
     * editor or set to {@code enabled: false}.
     */
    public void warnLiveProblems(int squads) {
        for (MapDef def : maps.values()) {
            if (!def.enabled()) {
                continue;
            }
            List<String> problems = publishProblems(def, squads);
            if (!problems.isEmpty()) {
                logger.warning("Map " + def.name() + " (" + def.id() + ") is live but would not pass /cv editor publish: "
                        + String.join("; ", problems) + ". Fix it with /cv editor start " + def.id()
                        + ", or set enabled: false in maps/" + def.id() + ".yml to take it out of matchmaking");
            }
        }
    }

    /** Logs every map's {@link #closeSpawns}. */
    public void warnCloseSpawns() {
        for (MapDef def : maps.values()) {
            for (CloseSpawn close : closeSpawns(def)) {
                logger.warning(String.format(Locale.ROOT, "Map %s (%s): %s post at %.1f %.1f %.1f is %.1f blocks from player spawn group %d"
                                + " (keep soldiers %d+ blocks away so a landing squad is not shot on the spot)",
                        def.name(), def.id(), close.kind(), close.post().x(), close.post().y(), close.post().z(), close.distance(),
                        close.group(), (int) SPAWN_CLEARANCE));
            }
        }
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
                y.getString("time", "day"),
                // Proving ground files written before the flag existed have no key: still a test map.
                y.getBoolean("test", id.equals(ProvingGroundGenerator.ID)),
                // Maps saved before drafts existed were all live.
                y.getBoolean("enabled", true));
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

    /** Writes maps/&lt;id&gt;.yml and puts the map in the registry as it is (published or not). */
    public void save(MapDef def) throws IOException {
        File target;
        try {
            target = file(def.id());
        } catch (IllegalArgumentException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        dir.mkdirs();
        write(def, target);
        maps.put(def.id(), def);
    }

    private static void write(MapDef def, File target) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.set("id", def.id());
        y.set("name", def.name());
        y.set("description", def.description());
        y.set("template", def.template());
        y.set("raid-minutes", def.raidSeconds() / 60);
        y.set("time", def.timeOfDay());
        y.set("test", def.test());
        y.set("enabled", def.enabled());
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
        y.save(target);
    }
}
