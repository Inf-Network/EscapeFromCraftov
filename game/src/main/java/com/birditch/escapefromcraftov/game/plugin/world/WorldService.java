package com.birditch.escapefromcraftov.game.plugin.world;

import com.birditch.escapefromcraftov.api.CraftovException;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/// Creates and destroys the dynamic per-craftov worlds, named
/// `craftov_<gameId>`.
///
/// Threading: template copies and directory deletion run on the IO executor;
/// every Bukkit call (world create, unload, teleport) runs on the main thread
/// via [com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler#runSync], and the
/// returned futures complete on the main thread. Both public methods and the
/// in-flight creation map are MAIN-THREAD confined.
public final class WorldService {

  private static final String WORLD_PREFIX = "craftov_";
  private static final String EDITOR_SOURCE_PREFIX = "craftov_source_";
  private static final String INSTANCE_MARKER_FILE = ".escapefromcraftov-instance";
  private static final String INSTANCE_MARKER_HEADER = "escapefromcraftov-instance-v1";
  private static final String UID_FILE = "uid.dat";
  private static final String SESSION_LOCK_FILE = "session.lock";

  private final WorldServiceDeps deps;
  private final Path worldContainer;
  /// In-flight creations by game id; entries are removed when the creation
  /// future settles so [#destroyInstanceWorld] can chain after any pending
  /// template copy instead of deleting a directory still being written to.
  private final Map<CraftovGameId, CompletableFuture<World>> pendingCreations = new HashMap<>();

  public WorldService(WorldServiceDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
    // Capture the Bukkit-owned path while wiring on the main thread. Async
    // cleanup and template copying then use only this immutable NIO value.
    this.worldContainer = deps.plugin().getServer().getWorldContainer().toPath()
        .toAbsolutePath().normalize();
  }

  /// Creates the instance world for `gameId`. When
  /// `<templateRoot>/<mapId>` exists it is copied into the server's world
  /// container (minus `uid.dat` and `session.lock`, so the copy loads as an
  /// independent world). A missing template fails production creation; the
  /// flat fallback is available only when explicitly enabled for development.
  public CompletableFuture<World> createInstanceWorld(CraftovGameId gameId, CraftovMapId mapId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(mapId, "mapId");
    String worldName = WORLD_PREFIX + gameId.value();
    Path worldDir = worldDirectory(worldName);
    Path templateDir = templateDirectory(mapId);
    CompletableFuture<World> future = new CompletableFuture<>();
    pendingCreations.put(gameId, future);
    future.whenComplete((_, _) -> pendingCreations.remove(gameId, future));
    CompletableFuture.supplyAsync(
            () -> copyTemplate(templateDir, worldDir, gameId, mapId), deps.ioExecutor())
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
                            future.complete(loadWorld(worldName, gameId, mapId, copied));
                          } catch (RuntimeException e) {
                            future.completeExceptionally(e);
                          }
                        }));
    return future;
  }

  /**
   * Removes disposable craftov worlds left by a previous crash or hard stop.
   *
   * <p>Only directories whose names start with {@code craftov_}, excluding editor
   * source worlds ({@code craftov_source_<map>}), and contain EscapeFromCraftov's ownership
   * marker are considered. A same-prefix administrator world is therefore
   * left untouched. Bukkit unloads loaded candidates on the main thread;
   * directory traversal and deletion stay on the IO executor so a large
   * abandoned world cannot pause the server tick.</p>
   */
  public CompletableFuture<Void> cleanupOrphanInstanceWorlds() {
    CompletableFuture<List<Path>> discovered = CompletableFuture.supplyAsync(
        this::findOrphanDirectories, deps.ioExecutor());
    return discovered.thenCompose(paths -> {
      CompletableFuture<List<Path>> unloaded = new CompletableFuture<>();
      deps.scheduler().runSync(() -> {
        try {
          List<Path> safeToDelete = new ArrayList<>();
          for (Path path : paths) {
            String worldName = path.getFileName().toString();
            World world = Bukkit.getWorld(worldName);
            if (world != null && !Bukkit.unloadWorld(world, false)) {
              deps.logger().warning("孤立实例世界无法卸载，保留目录: " + worldName);
              continue;
            }
            safeToDelete.add(path);
          }
          unloaded.complete(safeToDelete);
        } catch (RuntimeException e) {
          unloaded.completeExceptionally(e);
        }
      });
      return unloaded;
    }).thenAcceptAsync(paths -> {
      for (Path path : paths) {
        String worldName = path.getFileName().toString();
        deleteRecursivelyStrict(path);
        deps.logger().info("已清理上次运行遗留的实例世界: " + worldName);
      }
    }, deps.ioExecutor());
  }

  /// Unloads the instance world of `gameId` (teleporting stragglers to the
  /// default world spawn first) and deletes its directory on the IO executor.
  /// Failures are logged SEVERE and never thrown; the future always completes
  /// normally. When the world cannot be unloaded its files are left in place —
  /// deleting a loaded world's region files would corrupt it. When a creation
  /// for the same game is still in flight, destruction waits for it to settle
  /// (ignoring a failed creation) so the directory is never deleted while the
  /// async template copy may still write into it.
  public CompletableFuture<Void> destroyInstanceWorld(CraftovGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    CompletableFuture<World> pending = pendingCreations.get(gameId);
    if (pending == null) {
      return unloadAndDelete(gameId);
    }
    return pending.handle((_, _) -> gameId).thenCompose(this::unloadAndDelete);
  }

  private CompletableFuture<Void> unloadAndDelete(CraftovGameId gameId) {
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
    return worldContainer.resolve(worldName);
  }

  private Path templateDirectory(CraftovMapId mapId) {
    Path bundled = deps.templateRoot().resolve(mapId.value());
    if (Files.isRegularFile(bundled.resolve("level.dat"))) return bundled;
    return deps.maps().find(mapId.value())
        .map(definition -> worldContainer.resolve(definition.worldName()).normalize())
        .filter(path -> path.startsWith(worldContainer))
        .orElse(bundled);
  }

  /// Returns whether a template was found and copied. IO failures surface as
  /// [UncheckedIOException] and fail the creation future.
  private boolean copyTemplate(Path templateDir, Path worldDir, CraftovGameId gameId,
      CraftovMapId mapId) {
    if (Files.exists(worldDir)) {
      throw new CraftovException("实例世界目录已存在，为避免覆盖数据而拒绝创建: " + worldDir.getFileName());
    }
    if (!Files.isDirectory(templateDir)) {
      return false;
    }
    try {
      // Mark before copying so a crash during a large copy leaves a directory
      // that the next boot can identify and remove safely.
      Files.createDirectories(worldDir.getParent());
      Files.createDirectory(worldDir);
      writeInstanceMarker(worldDir, gameId, mapId);
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
            Path relative = source.relativize(dir);
            if (!shouldCopyTemplatePath(relative)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            Files.createDirectories(target.resolve(relative));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Path relative = source.relativize(file);
            if (!shouldCopyTemplatePath(relative)) {
              return FileVisitResult.CONTINUE;
            }
            if (file.getFileName().toString().equals(INSTANCE_MARKER_FILE)) {
              // A template can never replace the runtime ownership marker.
              return FileVisitResult.CONTINUE;
            }
            Files.copy(
                file,
                target.resolve(relative),
                StandardCopyOption.REPLACE_EXISTING);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /**
   * Entity region files contain persistent UUIDs. Copying them while the
   * authored source world is loaded makes Paper reject the duplicate entity in
   * a new instance. Skip every vanilla/custom-dimension {@code entities}
   * subtree; AI and other runtime actors are created after the game starts.
   */
  static boolean shouldCopyTemplatePath(Path relative) {
    if (relative == null) return false;
    for (Path element : relative) {
      if (element.toString().equals("entities")) return false;
    }
    return true;
  }

  /// Main thread only.
  private World loadWorld(String worldName, CraftovGameId gameId, CraftovMapId mapId,
      boolean templateCopied) {
    WorldCreator creator = new WorldCreator(worldName);
    if (!templateCopied) {
      if (!deps.allowFlatFallback()) {
        throw new CraftovException("Map template does not exist: "
            + deps.templateRoot().resolve(mapId.value()));
      }
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
      throw new CraftovException("World creation returned no world for " + worldName);
    }
    configureInstanceWorld(world);
    if (!templateCopied) {
      try {
        writeInstanceMarker(worldDirectory(worldName), gameId, mapId);
      } catch (IOException e) {
        throw new UncheckedIOException("无法写入实例世界所有权标记: " + worldName, e);
      }
    }
    return world;
  }

  /**
   * Applies raid-instance rules before the world is published as ready or any
   * participant is teleported into it. Craftov owns hostile population through
   * its AI runtime; vanilla natural spawning would otherwise fill a dark
   * template during the arrival countdown and attack players the instant their
   * pending invulnerability is released. Custom/plugin spawns remain available
   * when {@link GameRules#SPAWN_MOBS} is disabled.
   */
  static void configureInstanceWorld(World world) {
    applyInstanceWorldPolicy(world::setAutoSave,
        (rule, value) -> world.setGameRule(switch (rule) {
          case SHOW_ADVANCEMENTS -> GameRules.SHOW_ADVANCEMENT_MESSAGES;
          case NATURAL_MOB_SPAWNING -> GameRules.SPAWN_MOBS;
        }, value));
  }

  /** Pure policy seam: unit tests do not need a running Paper registry. */
  static void applyInstanceWorldPolicy(
      AutoSaveSetter autoSave, BooleanRuleSetter rules) {
    autoSave.set(false);
    rules.set(InstanceBooleanRule.SHOW_ADVANCEMENTS, false);
    rules.set(InstanceBooleanRule.NATURAL_MOB_SPAWNING, false);
  }

  enum InstanceBooleanRule { SHOW_ADVANCEMENTS, NATURAL_MOB_SPAWNING }

  @FunctionalInterface
  interface AutoSaveSetter {
    void set(boolean enabled);
  }

  @FunctionalInterface
  interface BooleanRuleSetter {
    void set(InstanceBooleanRule rule, boolean enabled);
  }

  private static void writeInstanceMarker(Path worldDir, CraftovGameId gameId, CraftovMapId mapId)
      throws IOException {
    String marker = INSTANCE_MARKER_HEADER + "\n"
        + "game-id=" + gameId.value() + "\n"
        + "map-id=" + mapId.value() + "\n";
    Files.writeString(worldDir.resolve(INSTANCE_MARKER_FILE), marker,
        java.nio.charset.StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
  }

  static boolean isOwnedInstanceWorld(Path worldDir) {
    if (worldDir == null || !Files.isDirectory(worldDir)) return false;
    Path marker = worldDir.resolve(INSTANCE_MARKER_FILE);
    if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker)) return false;
    try {
      return Files.readString(marker, java.nio.charset.StandardCharsets.UTF_8)
          .startsWith(INSTANCE_MARKER_HEADER + "\n");
    } catch (IOException ignored) {
      return false;
    }
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

  private void deleteRecursivelyStrict(Path directory) {
    if (!Files.exists(directory)) return;
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("无法清理实例世界目录: " + directory, e);
    }
  }

  private List<Path> findOrphanDirectories() {
    Path root = worldContainer;
    if (!Files.isDirectory(root)) return List.of();
    try (Stream<Path> paths = Files.list(root)) {
      return paths.filter(Files::isDirectory)
          .filter(path -> !Files.isSymbolicLink(path))
          .filter(path -> isDisposableInstanceWorldName(path.getFileName().toString()))
          .filter(path -> {
            if (isOwnedInstanceWorld(path)) return true;
            deps.logger().warning("发现未带 EscapeFromCraftov 所有权标记的 craftov_ 世界，保留目录: "
                + path.getFileName());
            return false;
          })
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("无法扫描孤立实例世界目录: " + root, e);
    }
  }

  static boolean isDisposableInstanceWorldName(String name) {
    return name != null && name.startsWith(WORLD_PREFIX)
        && !name.startsWith(EDITOR_SOURCE_PREFIX);
  }
}
