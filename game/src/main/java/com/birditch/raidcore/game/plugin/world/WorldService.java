package com.birditch.raidcore.game.plugin.world;

import com.birditch.raidcore.api.RaidException;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.common.check.Preconditions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;

/// Creates and destroys the dynamic per-raid worlds, named
/// `raid_<gameId>`.
///
/// Threading: template copies and directory deletion run on the IO executor;
/// every Bukkit call (world create, unload, teleport) runs on the main thread
/// via [com.birditch.raidcore.api.scheduler.RaidScheduler#runSync], and the
/// returned futures complete on the main thread.
public final class WorldService {

  private static final String WORLD_PREFIX = "raid_";
  private static final String UID_FILE = "uid.dat";
  private static final String SESSION_LOCK_FILE = "session.lock";

  private final WorldServiceDeps deps;

  public WorldService(WorldServiceDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
  }

  /// Creates the instance world for `gameId`. When
  /// `<templateRoot>/<mapId>` exists it is copied into the server's world
  /// container (minus `uid.dat` and `session.lock`, so the copy loads as an
  /// independent world); otherwise a flat fallback world is created and a
  /// WARNING is logged. The future fails when the template copy or world
  /// creation fails.
  public CompletableFuture<World> createInstanceWorld(RaidGameId gameId, RaidMapId mapId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(mapId, "mapId");
    String worldName = WORLD_PREFIX + gameId.value();
    Path worldDir = worldDirectory(worldName);
    Path templateDir = deps.templateRoot().resolve(mapId.value());
    CompletableFuture<World> future = new CompletableFuture<>();
    CompletableFuture.supplyAsync(() -> copyTemplate(templateDir, worldDir), deps.ioExecutor())
        .whenComplete(
            (copied, error) ->
                deps.scheduler()
                    .runSync(
                        () -> {
                          if (error != null) {
                            future.completeExceptionally(error);
                            return;
                          }
                          try {
                            future.complete(loadWorld(worldName, mapId, copied));
                          } catch (RuntimeException e) {
                            future.completeExceptionally(e);
                          }
                        }));
    return future;
  }

  /// Unloads the instance world of `gameId` (teleporting stragglers to the
  /// default world spawn first) and deletes its directory on the IO executor.
  /// Failures are logged SEVERE and never thrown; the future always completes
  /// normally. When the world cannot be unloaded its files are left in place —
  /// deleting a loaded world's region files would corrupt it.
  public CompletableFuture<Void> destroyInstanceWorld(RaidGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    String worldName = WORLD_PREFIX + gameId.value();
    Path worldDir = worldDirectory(worldName);
    CompletableFuture<Void> future = new CompletableFuture<>();
    deps.scheduler()
        .runSync(
            () -> {
              try {
                if (!unloadWorld(worldName)) {
                  deps.logger()
                      .severe("World " + worldName + " did not unload; leaving files in place");
                  future.complete(null);
                  return;
                }
                deps.ioExecutor()
                    .execute(
                        () -> {
                          deleteRecursively(worldDir);
                          future.complete(null);
                        });
              } catch (RuntimeException e) {
                deps.logger()
                    .log(Level.SEVERE, "Failed to destroy instance world " + worldName, e);
                future.complete(null);
              }
            });
    return future;
  }

  private Path worldDirectory(String worldName) {
    return deps.plugin().getServer().getWorldContainer().toPath().resolve(worldName);
  }

  /// Returns whether a template was found and copied. IO failures surface as
  /// [UncheckedIOException] and fail the creation future.
  private boolean copyTemplate(Path templateDir, Path worldDir) {
    if (!Files.isDirectory(templateDir)) {
      return false;
    }
    try {
      copyDirectory(templateDir, worldDir);
      Files.deleteIfExists(worldDir.resolve(UID_FILE));
      Files.deleteIfExists(worldDir.resolve(SESSION_LOCK_FILE));
      return true;
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to copy map template " + templateDir + " to " + worldDir, e);
    }
  }

  private static void copyDirectory(Path source, Path target) throws IOException {
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(target.resolve(source.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.copy(
                file,
                target.resolve(source.relativize(file)),
                StandardCopyOption.REPLACE_EXISTING);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /// Main thread only.
  private World loadWorld(String worldName, RaidMapId mapId, boolean templateCopied) {
    WorldCreator creator = new WorldCreator(worldName);
    if (!templateCopied) {
      deps.logger()
          .warning(
              "地图模板 '"
                  + mapId.value()
                  + "' 不存在,已退化为平坦世界 "
                  + worldName
                  + "(仅供调试;请把地图放到模板目录后重建实例)");
      creator.type(WorldType.FLAT);
      // Explicit preset: FLAT with empty generator settings makes vanilla log
      // "No key layers" and fall back anyway — spell the layers out instead.
      creator.generatorSettings(
          "{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},"
              + "{\"block\":\"minecraft:dirt\",\"height\":2},"
              + "{\"block\":\"minecraft:grass_block\",\"height\":1}],"
              + "\"biome\":\"minecraft:plains\"}");
    }
    World world = creator.createWorld();
    if (world == null) {
      throw new RaidException("World creation returned no world for " + worldName);
    }
    world.setAutoSave(false);
    world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
    return world;
  }

  /// Main thread only. Returns `true` when the world is not loaded or was
  /// unloaded successfully.
  private static boolean unloadWorld(String worldName) {
    World world = Bukkit.getWorld(worldName);
    if (world == null) {
      return true;
    }
    Location spawn = Bukkit.getWorlds().getFirst().getSpawnLocation();
    for (Player player : List.copyOf(world.getPlayers())) {
      player.teleport(spawn);
    }
    return Bukkit.unloadWorld(world, false);
  }

  private void deleteRecursively(Path directory) {
    if (!Files.exists(directory)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(directory)) {
      List<Path> deepestFirst = paths.sorted(Comparator.reverseOrder()).toList();
      for (Path path : deepestFirst) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      deps.logger().log(Level.SEVERE, "Failed to delete world directory " + directory, e);
    }
  }
}
