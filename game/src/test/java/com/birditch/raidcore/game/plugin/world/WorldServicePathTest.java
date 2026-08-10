package com.birditch.raidcore.game.plugin.world;

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
  void onlyDisposableRaidWorldNamesAreEligibleForRecovery() {
    assertTrue(WorldService.isDisposableInstanceWorldName("raid_abc123"));
    assertTrue(WorldService.isDisposableInstanceWorldName("raid_000000"));
    assertFalse(WorldService.isDisposableInstanceWorldName("raid_source_factory"));
    assertFalse(WorldService.isDisposableInstanceWorldName("world"));
    assertFalse(WorldService.isDisposableInstanceWorldName("raid"));
    assertFalse(WorldService.isDisposableInstanceWorldName(null));
  }

  @Test
  void recoveryRequiresRaidCoreOwnershipMarker() throws Exception {
    Path ordinary = Files.createDirectory(temporaryDirectory.resolve("raid_admin_world"));
    Path owned = Files.createDirectory(temporaryDirectory.resolve("raid_game_a"));
    Files.writeString(owned.resolve(".raidcore-instance"),
        "raidcore-instance-v1\ngame-id=game_a\nmap-id=factory\n");

    assertFalse(WorldService.isOwnedInstanceWorld(ordinary));
    assertTrue(WorldService.isOwnedInstanceWorld(owned));
  }
}
