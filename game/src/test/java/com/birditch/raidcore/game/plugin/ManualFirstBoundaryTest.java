package com.birditch.raidcore.game.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Keeps retired automatic-map experiments out of the production runtime. */
class ManualFirstBoundaryTest {

  private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();

  @Test
  void paperRuntimeCannotDependOnAutomaticMapExperiments() throws IOException {
    for (Path source : productionSources()) {
      String java = Files.readString(source);
      assertFalse(java.contains("com.birditch.raidcore.automap"), source.toString());
      assertFalse(java.contains("game.plugin.map.AutoSetup"), source.toString());
    }
  }

  @Test
  void commandTreeAndDefaultConfigExposeOnlyManualMapAuthoring() throws IOException {
    String command = Files.readString(REPOSITORY.resolve(
        "game/src/main/java/com/birditch/raidcore/game/plugin/command/RaidCommand.java"));
    String config = Files.readString(REPOSITORY.resolve("game/src/main/resources/config.yml"));
    String messages = Files.readString(REPOSITORY.resolve("game/src/main/resources/messages.yml"));

    assertFalse(command.contains("SetupCommands"));
    assertFalse(command.contains("quickstart"));
    assertFalse(config.contains("auto-setup:"));
    assertFalse(messages.contains("setup-auto:"));
    assertTrue(command.contains("ManualMapCommands.map(context)"));
    assertTrue(command.contains("EditorCommands.editor(context)"));
  }

  @Test
  void deployableJarsExcludeTheRetiredPackage() throws IOException {
    for (String module : List.of("game", "lobby")) {
      String build = Files.readString(REPOSITORY.resolve(module + "/build.gradle.kts"));
      assertTrue(build.contains("exclude(\"com/birditch/raidcore/automap/**\")"), module);
    }
  }

  private static List<Path> productionSources() throws IOException {
    List<Path> roots = List.of(
        REPOSITORY.resolve("game/src/main/java"),
        REPOSITORY.resolve("lobby/src/main/java"));
    try (var paths = roots.stream().flatMap(root -> {
      try {
        return Files.walk(root);
      } catch (IOException error) {
        throw new java.io.UncheckedIOException(error);
      }
    })) {
      return paths.filter(path -> path.toString().endsWith(".java")).toList();
    }
  }
}
