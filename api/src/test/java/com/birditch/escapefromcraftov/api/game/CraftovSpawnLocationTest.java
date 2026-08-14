package com.birditch.escapefromcraftov.api.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CraftovSpawnLocationTest {

  @Test
  void legacyConstructorsPreserveValidIdsAndNormalizePreviouslyAcceptedIds() {
    assertEquals("spawn_a", new CraftovSpawnLocation(
        "spawn_a", 0, 64, 0, 0, 0).routeGroup());
    CraftovSpawnLocation legacy = new CraftovSpawnLocation(
        "Spawn-A", 0, 64, 0, 0, 0);
    assertEquals(legacy.routeGroup(), new CraftovSpawnLocation(
        "Spawn-A", 0, 64, 0, 0, 0).routeGroup());
    assertTrue(legacy.routeGroup().matches("legacy_[a-z0-9]+"));
  }

  @Test
  void explicitRouteGroupRemainsStrict() {
    assertThrows(IllegalArgumentException.class, () -> new CraftovSpawnLocation(
        "Spawn-A", 0, 64, 0, 0, 0, "players", 1, null, "North-Side"));
  }
}
