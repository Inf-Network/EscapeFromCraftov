package com.birditch.escapefromcraftov.game.plugin.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldServicePathTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void onlyDisposableCraftovWorldNamesAreEligibleForRecovery() {
    assertTrue(WorldService.isDisposableInstanceWorldName("craftov_abc123"));
    assertTrue(WorldService.isDisposableInstanceWorldName("craftov_000000"));
    assertFalse(WorldService.isDisposableInstanceWorldName("craftov_source_factory"));
    assertFalse(WorldService.isDisposableInstanceWorldName("world"));
    assertFalse(WorldService.isDisposableInstanceWorldName("craftov"));
    assertFalse(WorldService.isDisposableInstanceWorldName(null));
  }

  @Test
  void recoveryRequiresEscapeFromCraftovOwnershipMarker() throws Exception {
    Path ordinary = Files.createDirectory(temporaryDirectory.resolve("craftov_admin_world"));
    Path owned = Files.createDirectory(temporaryDirectory.resolve("craftov_game_a"));
    Files.writeString(owned.resolve(".escapefromcraftov-instance"),
        "escapefromcraftov-instance-v1\ngame-id=game_a\nmap-id=factory\n");

    assertFalse(WorldService.isOwnedInstanceWorld(ordinary));
    assertTrue(WorldService.isOwnedInstanceWorld(owned));
  }

  @Test
  void templateCopyExcludesEntityRegionsInEveryDimension() {
    assertFalse(WorldService.shouldCopyTemplatePath(Path.of("entities", "r.0.0.mca")));
    assertFalse(WorldService.shouldCopyTemplatePath(
        Path.of("DIM-1", "entities", "r.0.0.mca")));
    assertFalse(WorldService.shouldCopyTemplatePath(
        Path.of("dimensions", "minecraft", "the_end", "entities", "r.0.0.mca")));
    assertTrue(WorldService.shouldCopyTemplatePath(Path.of("region", "r.0.0.mca")));
    assertTrue(WorldService.shouldCopyTemplatePath(Path.of("data", "raids.dat")));
  }
}
