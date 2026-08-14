package com.birditch.escapefromcraftov.game.plugin.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AiProjectileRegistryTest {

  @Test
  void projectileHitUntracksOnlyTheOwningGame() {
    AiProjectileRegistry registry = new AiProjectileRegistry();
    UUID hit = UUID.randomUUID();
    registry.track("game-a", hit);

    assertFalse(registry.untrack("game-b", hit));
    assertEquals(1, registry.size());
    assertTrue(registry.untrack("game-a", hit));
    assertEquals(0, registry.size());
    assertTrue(registry.drain("game-a").isEmpty());
  }

  @Test
  void invalidProjectilePruningIsBoundedAndFallsBackOnLookupFailure() {
    AiProjectileRegistry registry = new AiProjectileRegistry();
    for (int index = 0; index < 20; index++) {
      registry.track("game-a", UUID.randomUUID());
    }

    AiProjectileRegistry.PruneResult first = registry.prune(16, _ -> false);
    assertEquals(16, first.checked());
    assertEquals(16, first.removed());
    assertEquals(4, registry.size());

    AiProjectileRegistry.PruneResult failedLookup = registry.prune(1,
        _ -> { throw new IllegalStateException("entity lookup failed"); });
    assertEquals(1, failedLookup.checked());
    assertEquals(1, failedLookup.removed());
    assertEquals(3, registry.size());
  }

  @Test
  void endingOneGameDrainsOnlyItsTrackedProjectiles() {
    AiProjectileRegistry registry = new AiProjectileRegistry();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID otherGame = UUID.randomUUID();
    registry.track("game-a", first);
    registry.track("game-a", second);
    registry.track("game-a", first);
    registry.track("game-b", otherGame);

    assertEquals(List.of(first, second), registry.drain("game-a"));
    assertTrue(registry.drain("game-a").isEmpty());
    assertEquals(List.of(otherGame), registry.drain("game-b"));
  }
}
