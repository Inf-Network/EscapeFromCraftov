package com.birditch.escapefromcraftov.game.plugin.ownership;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ManualLootPickupPolicyTest {

  @Test
  void onlyAnUncancelledOwnershipAllowedPickupMayReachTheCarryCommit() {
    LootAccess priority = new LootAccess(LootAccess.Kind.PRIORITY, 10);
    LootAccess denied = new LootAccess(LootAccess.Kind.PROTECTED, 10);

    assertTrue(ManualLootPickupPolicy.mayTake(false, true, priority));
    assertFalse(ManualLootPickupPolicy.mayTake(true, true, priority));
    assertFalse(ManualLootPickupPolicy.mayTake(false, true, denied));
    assertFalse(ManualLootPickupPolicy.mayTake(false, false, priority));
  }
}
