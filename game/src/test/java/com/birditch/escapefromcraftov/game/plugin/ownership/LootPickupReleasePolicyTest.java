package com.birditch.escapefromcraftov.game.plugin.ownership;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LootPickupReleasePolicyTest {

  @Test
  void onlyFinalUncancelledAuthorizedPickupReleasesTransientTags() {
    LootAccess priority = new LootAccess(LootAccess.Kind.PRIORITY, 12);
    LootAccess denied = new LootAccess(LootAccess.Kind.PROTECTED, 12);

    assertTrue(LootPickupReleasePolicy.shouldRelease(false, 0, priority));
    assertFalse(LootPickupReleasePolicy.shouldRelease(true, 0, priority));
    assertFalse(LootPickupReleasePolicy.shouldRelease(false, 1, priority));
    assertFalse(LootPickupReleasePolicy.shouldRelease(false, 0, denied));
  }
}
