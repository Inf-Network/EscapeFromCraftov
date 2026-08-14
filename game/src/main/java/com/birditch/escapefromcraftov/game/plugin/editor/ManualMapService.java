package com.birditch.escapefromcraftov.game.plugin.editor;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.map.EditorSession;
import com.birditch.escapefromcraftov.map.ManualMapReferences;
import com.birditch.escapefromcraftov.map.ManualMapValidationResult;
import com.birditch.escapefromcraftov.map.ManualMapValidationSettings;
import com.birditch.escapefromcraftov.map.ManualMapValidator;
import com.birditch.escapefromcraftov.map.ManualCraftovMap;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/// Coordinates validation, optimistic saves, enablement and explicit rollback.
public final class ManualMapService {

  private final ManualMapStore store;
  private final ManualMapRegistry registry;
  private final ManualMapValidator validator;
  private final ManualMapValidationSettings settings;
  private final Function<String, ManualMapReferences> references;
  private final @Nullable Path templateRoot;
  private final @Nullable Path worldRoot;

  public ManualMapService(ManualMapStore store, ManualMapRegistry registry,
      ManualMapValidationSettings settings, Function<String, ManualMapReferences> references) {
    this(store, registry, settings, references, null, null);
  }

  public ManualMapService(ManualMapStore store, ManualMapRegistry registry,
      ManualMapValidationSettings settings, Function<String, ManualMapReferences> references,
      @Nullable Path templateRoot) {
    this(store, registry, settings, references, templateRoot, null);
  }

  public ManualMapService(ManualMapStore store, ManualMapRegistry registry,
      ManualMapValidationSettings settings, Function<String, ManualMapReferences> references,
      @Nullable Path templateRoot, @Nullable Path worldRoot) {
    this.store = Preconditions.checkNotNull(store, "store");
    this.registry = Preconditions.checkNotNull(registry, "registry");
    this.settings = Preconditions.checkNotNull(settings, "settings");
    this.references = Preconditions.checkNotNull(references, "references");
    this.templateRoot = templateRoot;
    this.worldRoot = worldRoot;
    this.validator = new ManualMapValidator();
  }

  /**
   * Imports either a normal Minecraft world (`level.dat`) or a complete
   * EscapeFromCraftov package (`map.yml`, optionally with world files). World files are
   * copied into a dedicated editor source world when a world container was
   * configured; the original folder is never changed. A raw world gets a
   * disabled draft, while a complete package preserves its authored objects.
   */
  public synchronized ManualCraftovMap importPackage(String mapId, Path packageDirectory) {
    Preconditions.checkNotNull(packageDirectory, "packageDirectory");
    Preconditions.checkState(templateRoot != null, "地图模板目录未配置");
    Preconditions.checkArgument(registry.find(mapId).isEmpty(), "地图已存在: " + mapId);
    Path source = packageDirectory.toAbsolutePath().normalize();
    Preconditions.checkArgument(!Files.isSymbolicLink(source), "地图包目录不能是符号链接: "
        + source.getFileName());
    Preconditions.checkArgument(Files.isDirectory(source), "地图包目录不存在: " + source);
    boolean hasManifest = Files.isRegularFile(source.resolve("map.yml"));
    boolean hasWorld = Files.isRegularFile(source.resolve("level.dat"));
    Preconditions.checkArgument(hasManifest || hasWorld,
        "地图目录必须包含 level.dat 或 map.yml: " + source.getFileName());
    String editorWorldName = "craftov_source_" + mapId;
    ManualCraftovMap packageMap = hasManifest
        ? store.loadPackage(source)
        : ManualCraftovMap.create(mapId, editorWorldName);
    ManualCraftovMap imported = packageMap.copyAs(mapId,
        hasWorld && worldRoot != null ? editorWorldName : packageMap.worldName());
    Path target = hasWorld && worldRoot != null
        ? worldRoot.resolve(editorWorldName).toAbsolutePath().normalize()
        : templateRoot.resolve(mapId).toAbsolutePath().normalize();
    Path targetRoot = hasWorld && worldRoot != null
        ? worldRoot.toAbsolutePath().normalize()
        : templateRoot.toAbsolutePath().normalize();
    Preconditions.checkArgument(target.startsWith(targetRoot), "地图目标目录越界: " + target);
    Preconditions.checkArgument(!source.equals(target), "不能把地图导入到自身");
    Preconditions.checkArgument(!target.startsWith(source), "地图导入目标不能位于来源目录内部");
    Preconditions.checkArgument(!Files.exists(target), "地图导入目标已存在: " + target);
    Path staging = null;
    boolean moved = false;
    boolean registered = false;
    try {
      Files.createDirectories(targetRoot);
      staging = Files.createTempDirectory(targetRoot, ".import-" + mapId + "-");
      copyDirectory(source, staging);
      try {
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
        Files.move(staging, target);
      }
      moved = true;
      staging = null;
      ManualCraftovMap persisted = store.save(imported);
      registry.put(persisted);
      registered = true;
      return persisted;
    } catch (IOException e) {
      throw new com.birditch.escapefromcraftov.api.CraftovException("无法复制地图模板: " + source, e);
    } catch (RuntimeException e) {
      throw e;
    } finally {
      if (staging != null) deleteRecursively(staging);
      if (moved && !registered) {
        deleteRecursively(target);
      }
    }
  }

  public ManualCraftovMap create(String mapId, String worldName) {
    Preconditions.checkArgument(registry.find(mapId).isEmpty(), "地图已存在: " + mapId);
    ManualCraftovMap persisted = store.save(ManualCraftovMap.create(mapId, worldName));
    registry.put(persisted);
    return persisted;
  }

  public ManualCraftovMap save(EditorSession session) {
    Preconditions.checkNotNull(session, "session");
    ManualMapValidationResult result = validate(session.draft());
    session.setValidationResult(result);
    if (session.draft().enabled()) {
      if (result.hasErrors()) {
        throw new IllegalStateException("已启用地图的编辑稿存在 " + result.errorCount()
            + " 个 ERROR；请先修复或禁用地图");
      }
    }
    ManualCraftovMap persisted = store.save(session.draft());
    registry.put(persisted);
    session.markSaved(persisted);
    session.setValidationResult(result);
    return persisted;
  }

  public ManualMapValidationResult validate(ManualCraftovMap map) {
    return validator.validate(map, references.apply(map.worldName()), settings);
  }

  public ManualCraftovMap setEnabled(String mapId, boolean enabled) {
    ManualCraftovMap current = registry.find(mapId)
        .orElseThrow(() -> new IllegalArgumentException("地图不存在: " + mapId));
    if (enabled) {
      ManualMapValidationResult result = validate(current);
      if (result.hasErrors()) {
        throw new IllegalStateException("地图验证失败，存在 " + result.errorCount()
            + " 个 ERROR；请执行 /craftov editor validate");
      }
    }
    ManualCraftovMap persisted = store.save(current.withEnabled(enabled));
    registry.put(persisted);
    return persisted;
  }

  public ManualCraftovMap rollback(String mapId, long revision) {
    ManualCraftovMap restored = store.rollback(mapId, revision);
    ManualMapValidationResult result = validate(restored);
    if (restored.enabled() && result.hasErrors()) {
      restored = store.save(restored.withEnabled(false));
      result = validate(restored);
    }
    registry.put(restored);
    return restored;
  }

  public List<Long> revisions(String mapId) {
    return store.revisions(mapId);
  }

  private static void copyDirectory(Path source, Path target) throws IOException {
    Files.walkFileTree(source, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path directory, java.nio.file.attribute.BasicFileAttributes attrs)
          throws IOException {
        if (attrs.isSymbolicLink()) {
          throw new IOException("地图包不能包含符号链接目录: " + directory);
        }
        Path relative = source.relativize(directory);
        if (relative.getFileName() != null
            && relative.getFileName().toString().equals("revisions")) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        Path destination = target.resolve(relative).normalize();
        if (!destination.startsWith(target)) {
          throw new IOException("地图包目录越界: " + relative);
        }
        Files.createDirectories(destination);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
          throws IOException {
        if (attrs.isSymbolicLink()) {
          throw new IOException("地图包不能包含符号链接文件: " + file);
        }
        Path relativePath = source.relativize(file);
        String fileName = file.getFileName().toString();
        if ((relativePath.getNameCount() == 1 && fileName.equals("map.yml"))
            || fileName.equals("uid.dat") || fileName.equals("session.lock")) {
          return FileVisitResult.CONTINUE;
        }
        Path destination = target.resolve(relativePath).normalize();
        if (!destination.startsWith(target)) {
          throw new IOException("地图包文件越界: " + relativePath);
        }
        Files.copy(file, destination,
            StandardCopyOption.REPLACE_EXISTING);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private static void deleteRecursively(Path directory) {
    if (!Files.exists(directory)) return;
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException ignored) {
      // The original source remains untouched; a later admin retry can clean
      // an abandoned staging directory safely.
    }
  }
}
