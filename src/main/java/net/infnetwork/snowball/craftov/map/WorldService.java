package net.infnetwork.snowball.craftov.map;

import net.infnetwork.snowball.craftov.map.gen.MapGenerator;
import net.infnetwork.snowball.craftov.map.gen.ProvingGroundGenerator;
import net.infnetwork.snowball.craftov.map.gen.RustPortGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Creates template worlds, clones them for raids and disposes of finished raid worlds. The map
 * editor changes a published map on a working copy of its template (see {@link #copyForEditing})
 * that only replaces the template when it is published ({@link #replaceTemplate}).
 */
public final class WorldService {

    public static final String RAID_PREFIX = "craftov_raid_";
    /** The map editor's working copies of published maps' templates. */
    public static final String EDIT_PREFIX = "craftov_edit_";
    private static final Set<String> SKIP = Set.of("uid.dat", "session.lock");

    private final Plugin plugin;
    private final MapRegistry maps;
    private final AtomicInteger counter = new AtomicInteger();
    /** Templates with raid copies under way on scheduler threads, and how many. Server thread only. */
    private final Map<String, Integer> copying = new HashMap<>();

    public WorldService(Plugin plugin, MapRegistry maps) {
        this.plugin = plugin;
        this.maps = maps;
    }

    public static WorldCreator creator(String name) {
        return new WorldCreator(name).generator(new VoidGenerator())
                .generateStructures(false).keepSpawnLoaded(net.kyori.adventure.util.TriState.FALSE);
    }

    public static void applyRules(World world, boolean raid) {
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        world.setGameRule(GameRule.NATURAL_REGENERATION, !raid);
        world.setGameRule(GameRule.LOCATOR_BAR, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_INSOMNIA, false);
        world.setGameRule(GameRule.DO_ENTITY_DROPS, false);
        world.setGameRule(GameRule.DO_MOB_LOOT, false);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRule.SPECTATORS_GENERATE_CHUNKS, false);
        // Not HARD: vanilla multiplies damage from mobs to players by 1.5 there (after our own
        // numbers, before EntityDamageEvent), which made soldiers hit half again harder than
        // ai.damage-multiplier says. Hunger is pinned and mobs have no vanilla goals, so nothing
        // else here depends on the difficulty.
        world.setDifficulty(Difficulty.NORMAL);
        world.setStorm(false);
        world.setThundering(false);
        world.setAutoSave(!raid);
    }

    /** @param migrate brings a saved definition up to date with markers the generator has since moved */
    private record Builtin(String id, String name, Function<World, MapGenerator> factory, int spawnX, int spawnY,
                           int spawnZ, java.util.function.UnaryOperator<MapDef> migrate) {
    }

    private static final List<Builtin> BUILTINS = List.of(
            new Builtin(RustPortGenerator.ID, "锈港", RustPortGenerator::new, 104, 66, 104, RustPortGenerator::migrate),
            new Builtin(ProvingGroundGenerator.ID, "试验场", ProvingGroundGenerator::new, 60, 65, 92, def -> def));

    /** Generates every built-in map whose template or definition is missing. */
    public void ensureBuiltins() {
        for (Builtin builtin : BUILTINS) {
            ensure(builtin);
        }
    }

    private void ensure(Builtin builtin) {
        String template = "craftov_tpl_" + builtin.id();
        File folder = new File(Bukkit.getWorldContainer(), template);
        MapDef saved = maps.get(builtin.id());
        boolean haveDef = saved != null;
        if (folder.exists() && haveDef) {
            MapDef migrated = builtin.migrate().apply(saved);
            if (migrated != saved) {
                try {
                    maps.save(migrated);
                    plugin.getLogger().info("Moved soldier posts and spawn points in maps/" + builtin.id()
                            + ".yml that were too close to each other");
                } catch (IOException ex) {
                    plugin.getLogger().severe("Could not update map definition: " + ex.getMessage());
                }
            }
            return;
        }
        if (maps.broken().contains(builtin.id())) {
            // Regenerating would write the default definition over the admin's file (and replay
            // every block into the template world): leave both alone until the file is fixed.
            plugin.getLogger().severe("Built-in map '" + builtin.name() + "' is not available: maps/" + builtin.id()
                    + ".yml is invalid (see above). It is not regenerated while that file exists.");
            return;
        }
        long start = System.currentTimeMillis();
        plugin.getLogger().info("Generating built-in raid map '" + builtin.name() + "' (first start only, this takes a little while)...");
        World world = creator(template).createWorld();
        if (world == null) {
            plugin.getLogger().severe("Could not create template world " + template);
            return;
        }
        applyRules(world, false);
        MapGenerator generator = builtin.factory().apply(world);
        MapDef def = generator.generate();
        world.setSpawnLocation(builtin.spawnX(), builtin.spawnY(), builtin.spawnZ());
        world.save();
        try {
            maps.save(def);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save map definition: " + ex.getMessage());
        }
        Bukkit.unloadWorld(world, true);
        plugin.getLogger().info("Built '" + builtin.name() + "' with " + generator.blocksPlaced() + " blocks in "
                + (System.currentTimeMillis() - start) + " ms (" + def.containers().size() + " containers, "
                + def.extracts().size() + " extracts)");
    }

    // ------------------------------------------------------------------ editor working copies

    /**
     * The working copy's world name for a map: craftov_edit_&lt;id&gt;. An id that is not a plain
     * folder name (one saved before ids were checked, "Old-Map" or "废弃工厂") is reduced to one,
     * with its hash added so two such ids do not share a copy.
     */
    public static String editCopy(String mapId) {
        String safe = mapId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return EDIT_PREFIX + (safe.equals(mapId) ? safe : safe + "_" + Integer.toHexString(mapId.hashCode()));
    }

    /** Whether a world folder of this name exists in the world container. */
    public static boolean exists(String name) {
        return new File(Bukkit.getWorldContainer(), name).isDirectory();
    }

    /** Loads a world folder (or returns it if loaded) with the rules of an editing world. */
    public @Nullable World load(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null) {
            world = creator(name).createWorld();
        }
        if (world != null) {
            applyRules(world, false);
        }
        return world;
    }

    /**
     * Replaces the working copy with a fresh copy of the template and loads it. The copy must not
     * be loaded. Returns null on failure (logged).
     */
    public @Nullable World copyForEditing(String template, String copy) {
        File container = Bukkit.getWorldContainer();
        Path target = new File(container, copy).toPath();
        if (Bukkit.getWorld(copy) != null) {
            plugin.getLogger().severe("Cannot refresh the working copy " + copy + " while it is loaded");
            return null;
        }
        World loadedTemplate = Bukkit.getWorld(template);
        if (loadedTemplate != null) {
            loadedTemplate.save(true);
        }
        try {
            deleteTree(target);
            copy(new File(container, template).toPath(), target);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not copy template " + template + " to " + copy + ": " + ex.getMessage());
            return null;
        }
        return load(copy);
    }

    /** Unloads a world (players must have been moved out). Returns whether it is unloaded now. */
    public boolean unload(String name, boolean save) {
        World world = Bukkit.getWorld(name);
        return world == null || Bukkit.unloadWorld(world, save);
    }

    /** Unloads a world without saving and deletes its folder. Returns false if it could not be unloaded. */
    public boolean delete(String name) {
        if (!unload(name, false)) {
            return false;
        }
        deleteQuietly(new File(Bukkit.getWorldContainer(), name).toPath());
        return true;
    }

    /**
     * Publishes a working copy: the copy's folder becomes the template's, and the template's
     * becomes &lt;template&gt;.prev (the one before that is deleted), so a publish can be undone
     * by hand. Both worlds are unloaded first (the copy saved, the template not: nothing may have
     * changed it), so players must have been moved out. Refused while a raid's copy of the
     * template is under way ({@link #copyRaidTemplate}), so no raid can see the folder half
     * replaced. Returns null when done, or why not.
     */
    public @Nullable String replaceTemplate(String template, String copy) {
        if (copying(template)) {
            return "有对局正在复制这张图的模板，几秒后再试";
        }
        if (!unload(copy, true)) {
            return "编辑副本卸载失败（里面还有人？）";
        }
        if (!unload(template, false)) {
            return "模板世界卸载失败（里面还有人？）";
        }
        Path container = Bukkit.getWorldContainer().toPath();
        Path live = container.resolve(template);
        Path edited = container.resolve(copy);
        Path prev = container.resolve(template + ".prev");
        if (!Files.isDirectory(edited)) {
            return "编辑副本 " + copy + " 不存在";
        }
        try {
            deleteTree(prev);
            if (Files.exists(live)) {
                Files.move(live, prev, StandardCopyOption.ATOMIC_MOVE);
            }
            try {
                Files.move(edited, live, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                if (Files.exists(prev) && !Files.exists(live)) {
                    Files.move(prev, live, StandardCopyOption.ATOMIC_MOVE);
                }
                throw ex;
            }
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not replace template " + template + " with " + copy + ": " + ex);
            return "替换模板失败：" + ex.getMessage();
        }
        return null;
    }

    /**
     * First half of a raid's world: copies the map's template to a new raid folder. The copy runs
     * on a scheduler thread, since copying on the server thread held up every running raid while
     * it lasted (issue #45). The future completes on the server thread with the folder's name, or
     * with null when the copy failed (logged). A template that is loaded (open in the editor) is
     * saved and copied right here instead, as before: its chunks could be written to disk while a
     * copy on another thread reads them.
     *
     * <p>While a copy of a template is under way, {@link #replaceTemplate} refuses to swap that
     * template's folder. If the plugin is disabled before the copy ends, the future never
     * completes and the folder is deleted ({@link #cleanupStale} removes what is left on the next
     * start).
     */
    public CompletableFuture<@Nullable String> copyRaidTemplate(MapDef map) {
        CompletableFuture<@Nullable String> result = new CompletableFuture<>();
        String template = map.template();
        Path container = Bukkit.getWorldContainer().toPath();
        Path source = container.resolve(template);
        if (!Files.isDirectory(source)) {
            plugin.getLogger().severe("Template world missing: " + source);
            result.complete(null);
            return result;
        }
        String name = RAID_PREFIX + System.currentTimeMillis() % 100000 + "_" + counter.incrementAndGet();
        Path target = container.resolve(name);
        World loadedTemplate = Bukkit.getWorld(template);
        if (loadedTemplate != null) {
            // Flushed before the folder is copied: save() alone only queues chunks for the IO
            // thread, which may still be writing region files while they are read.
            loadedTemplate.save(true);
            result.complete(copyTemplate(template, source, target) ? name : null);
            return result;
        }
        copying.merge(template, 1, Integer::sum);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean copied = false;
            try {
                copied = copyTemplate(template, source, target);
            } finally {
                // Even when the copy died of something copyTemplate does not catch (an Error): the
                // count must drop, or the map could not be published again until a restart, and
                // the launch must hear of it rather than wait out its timeout. Its half-copied
                // folder goes too (copyTemplate removes it itself for what it catches).
                if (!copied) {
                    deleteQuietly(target);
                }
                @Nullable String folder = copied ? name : null;
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        copying.computeIfPresent(template, (key, count) -> count > 1 ? count - 1 : null);
                        result.complete(folder);
                    });
                } catch (IllegalPluginAccessException ex) {
                    // Disabled meanwhile: nobody is waiting for this world any more.
                    deleteQuietly(target);
                }
            }
        });
        return result;
    }

    /** Whether a raid's copy of this template is under way on a scheduler thread. */
    public boolean copying(String template) {
        return copying.containsKey(template);
    }

    /**
     * Raid copies of templates under way on scheduler threads, for /cv raids: what makes
     * {@link #replaceTemplate} refuse, counted even when nobody waits for the copy any more (its
     * launch timed out, or everyone in it left).
     */
    public int copies() {
        return copying.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Copies a template folder for a raid, logging how long it took. A failed copy is removed. */
    private boolean copyTemplate(String template, Path source, Path target) {
        long start = System.nanoTime();
        try {
            long bytes = copy(source, target);
            plugin.getLogger().info("Copied template " + template + " to " + target.getFileName() + " (" + megabytes(bytes)
                    + ") in " + millis(System.nanoTime() - start));
            return true;
        } catch (IOException | RuntimeException ex) {
            // RuntimeException too: a file system provider may throw an UncheckedIOException, or a
            // SecurityException, while the tree is walked.
            plugin.getLogger().severe("Could not copy template " + template + ": " + ex);
            deleteQuietly(target);
            return false;
        }
    }

    /**
     * Second half of a raid's world, on the server thread (Bukkit allows nothing else): loads a
     * folder {@link #copyRaidTemplate} made. Returns null on failure, with the folder deleted.
     */
    public @Nullable World loadRaidWorld(String name, MapDef map) {
        long start = System.nanoTime();
        World world = creator(name).createWorld();
        // The part of a raid's start that still holds up every running raid.
        plugin.getLogger().info("Loaded raid world " + name + " in " + millis(System.nanoTime() - start));
        if (world == null) {
            plugin.getLogger().severe("Could not load raid world " + name);
            discardRaidCopy(name);
            return null;
        }
        applyRules(world, true);
        world.setTime(switch (map.timeOfDay()) {
            case "night" -> 18000;
            case "dawn" -> 23500;
            case "day" -> 3000;
            default -> 12300;
        });
        return world;
    }

    /** Deletes a raid folder that was copied but will not be loaded (its launch was called off). */
    public void discardRaidCopy(String name) {
        Path folder = Bukkit.getWorldContainer().toPath().resolve(name);
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteQuietly(folder));
        } else {
            deleteQuietly(folder);
        }
    }

    public void disposeRaidWorld(World world) {
        String name = world.getName();
        File folder = world.getWorldFolder();
        world.getPlayers().forEach(p -> p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()));
        if (!Bukkit.unloadWorld(world, false)) {
            plugin.getLogger().warning("Could not unload raid world " + name + "; it will be removed on next start");
            return;
        }
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteQuietly(folder.toPath()));
        } else {
            // Shutting down: the scheduler refuses new tasks, and throwing here would leave every
            // later raid without its players sent home.
            deleteQuietly(folder.toPath());
        }
    }

    /** Removes leftover raid worlds from a previous crash. */
    public void cleanupStale() {
        File[] folders = Bukkit.getWorldContainer().listFiles((dir, name) -> name.startsWith(RAID_PREFIX));
        if (folders == null) {
            return;
        }
        for (File folder : folders) {
            if (Bukkit.getWorld(folder.getName()) == null) {
                deleteQuietly(folder.toPath());
            }
        }
    }

    /** Copies a world folder (without its lock and uid). Returns the bytes copied. */
    private static long copy(Path from, Path to) throws IOException {
        long[] bytes = {0};
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!SKIP.contains(file.getFileName().toString())) {
                    Files.copy(file, to.resolve(from.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                    bytes[0] += attrs.size();
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return bytes[0];
    }

    private static String millis(long nanos) {
        return nanos / 1_000_000 + " ms";
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private void deleteQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not delete " + root + ": " + ex.getMessage());
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
