package com.birditch.escapefromcraftov.game.plugin.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiRuntimePolicyTest {

  @Test
  void targetPopulationIsDeterministicAndAlwaysInsideAuthoredRange() {
    int first = AiRuntimePolicy.targetPopulation(2, 4, 712349L);
    assertEquals(first, AiRuntimePolicy.targetPopulation(2, 4, 712349L));
    for (long seed = 0; seed < 100; seed++) {
      int target = AiRuntimePolicy.targetPopulation(2, 4, seed);
      assertTrue(target >= 2 && target <= 4);
    }
    assertEquals(0, AiRuntimePolicy.targetPopulation(0, 0, 1L));
  }

  @Test
  void difficultyMonotonicallyRaisesCombatStatsAndCapsEquipmentTier() {
    var normal = AiRuntimePolicy.difficulty(1.0);
    var elite = AiRuntimePolicy.difficulty(3.0);
    assertTrue(elite.healthMultiplier() > normal.healthMultiplier());
    assertTrue(elite.damageMultiplier() > normal.damageMultiplier());
    assertTrue(elite.movementMultiplier() > normal.movementMultiplier());
    assertEquals(1, normal.equipmentTier());
    assertEquals(3, elite.equipmentTier());
    assertEquals(3, AiRuntimePolicy.difficulty(100).equipmentTier());
  }
}
