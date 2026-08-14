package com.birditch.escapefromcraftov.game.plugin.carry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RaidCarryEventPolicyTest {

  @Test
  void aPreCancelledOrInactiveEventCannotCommitARealMutation() {
    assertTrue(RaidCarryEventPolicy.mayCommit(false, true));
    assertFalse(RaidCarryEventPolicy.mayCommit(true, true));
    assertFalse(RaidCarryEventPolicy.mayCommit(false, false));
  }

  @Test
  void inventoryQSeparatesOneWhileControlQUsesTheWholeAnchor() {
    assertEquals(1, RaidCarryEventPolicy.clickedDropQuantity(true, 12));
    assertEquals(12, RaidCarryEventPolicy.clickedDropQuantity(false, 12));
    assertEquals(1, RaidCarryEventPolicy.clickedDropQuantity(true, 1));
  }
}
