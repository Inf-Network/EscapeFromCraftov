package com.birditch.escapefromcraftov.game.plugin.carry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RaidCarryLayoutTest {

  @Test
  void opensFourPocketsThenTheConfiguredContainerCells() {
    assertEquals(List.of(3, 4, 5, 6, 9, 10, 11), RaidCarryLayout.cargoSlots(3));
    assertEquals(24, RaidCarryLayout.lockedStorageSlots(3).size());
    assertEquals(12, RaidCarryLayout.lockedStorageSlots(3).getFirst());
  }

  @Test
  void equipmentCellsNeverBecomeCargo() {
    assertTrue(RaidCarryLayout.reservedEquipmentSlot(0));
    assertTrue(RaidCarryLayout.reservedEquipmentSlot(7));
    assertFalse(RaidCarryLayout.cargoSlot(7, 27));
    assertTrue(RaidCarryLayout.cargoSlot(35, 27));
  }

  @Test
  void shrinkingRequiresEveryOccupiedPhysicalCellToStillFit() {
    assertTrue(RaidCarryLayout.canShrink(10, 6));
    assertFalse(RaidCarryLayout.canShrink(11, 6));
  }
}
