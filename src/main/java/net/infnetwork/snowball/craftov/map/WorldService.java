package net.infnetwork.snowball.craftov.map;

import net.infnetwork.snowball.craftov.map.gen.MapGenerator;
import net.infnetwork.snowball.craftov.map.gen.ProvingGroundGenerator;
import net.infnetwork.snowball.craftov.map.gen.RustPortGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Creates template worlds, clones them for raids and disposes of finished raid worlds. */
public final class WorldService {

    public static final String RAID_PREFIX = "craftov_raid_";
    private static final Set<String> SKIP = Set.of("uid.dat", "session.lock");

    private final Plugin plugin;
    private final MapRegistry maps;
    private final AtomicInteger counter = new AtomicInteger();

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
        world.setDifficulty(Difficulty.HARD);
        world.setStorm(false);
        world.setThundering(false);
        world.setAutoSave(!raid);
    }

    private record Builtin(String id, String name, Function<World, MapGenerator> factory, int spawnX, int spawnY,
                           int spawnZ) {
    }

    private static final List<Builtin> BUILTINS = List.of(
            new Builtin(RustPortGenerator.ID, "锈港", RustPortGenerator::new, 104, 66, 104),
            new Builtin(ProvingGroundGenerator.ID, "试验场", ProvingGroundGenerator::new, 60, 65, 92));

    /** Generates every built-in map whose template or definition is missing. */
    public void ensureBuiltins() {
        for (Builtin builtin : BUILTINS) {
            ensure(builtin);
        }
    }

    private void ensure(Builtin builtin) {
        String template = "craftov_tpl_" + builtin.id();
        File folder = new File(Bukkit.getWorldContainer(), template);
        boolean haveDef = maps.get(builtin.id()) != null;
        if (folder.exists() && haveDef) {
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

    /** Copies a template world and loads it for one raid. Returns null on failure. */
    public @Nullable World createRaidWorld(MapDef map) {
        File container = Bukkit.getWorldContainer();
        File source = new File(container, map.template());
        if (!source.exists()) {
            plugin.getLogger().severe("Template world missing: " + source);
            return null;
        }
        World loadedTemplate = Bukkit.getWorld(map.template());
        if (loadedTemplate != null) {
            loadedTemplate.save();
        }
        String name = RAID_PREFIX + System.currentTimeMillis() % 100000 + "_" + counter.incrementAndGet();
        File target = new File(container, name);
        try {
            copy(source.toPath(), target.toPath());
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not copy template " + map.template() + ": " + ex.getMessage());
            return null;
        }
        World world = creator(name).createWorld();
        if (world == null) {
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

    public void disposeRaidWorld(World world) {
        String name = world.getName();
        File folder = world.getWorldFolder();
        world.getPlayers().forEach(p -> p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()));
        if (!Bukkit.unloadWorld(world, false)) {
            plugin.getLogger().warning("Could not unload raid world " + name + "; it will be removed on next start");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteQuietly(folder.toPath()));
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

    private static void copy(Path from, Path to) throws IOException {
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
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteQuietly(Path root) {
        try {
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
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not delete " + root + ": " + ex.getMessage());
        }
    }
}
