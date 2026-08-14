package com.birditch.escapefromcraftov.game.plugin.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import java.util.List;
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

  @Test
  void effectiveDifficultyMapsToCraftovTierTablesAndRaidRulesScaleRolls() {
    assertEquals("craftov_tier_1", AiRuntimePolicy.loot(
        1.0, RaidMode.NORMAL, RaidDifficulty.NORMAL).tableId().value());
    assertEquals("craftov_tier_2", AiRuntimePolicy.loot(
        1.01, RaidMode.NORMAL, RaidDifficulty.NORMAL).tableId().value());
    assertEquals("craftov_tier_3", AiRuntimePolicy.loot(
        2.5, RaidMode.NORMAL, RaidDifficulty.NORMAL).tableId().value());
    assertEquals("craftov_tier_4", AiRuntimePolicy.loot(
        9.0, RaidMode.NORMAL, RaidDifficulty.NORMAL).tableId().value());
    assertEquals(1, AiRuntimePolicy.loot(
        1.0, RaidMode.NORMAL, RaidDifficulty.EASY).rolls());
    assertEquals(3, AiRuntimePolicy.loot(
        1.0, RaidMode.CONFIDENTIAL, RaidDifficulty.HARD).rolls());
  }

  @Test
  void canonicalProfileControlsAiDropRollsWithoutChangingDifficultyTierSelection() {
    RaidActionProfile profile = new RaidActionProfile(RaidTierId.of("custom"), "自定义", 0,
        1.4, 4.0, 0, List.of());
    var loot = AiRuntimePolicy.loot(2.2, RaidMode.NORMAL, RaidDifficulty.EASY,
        profile, 99);
    assertEquals("craftov_tier_3", loot.tableId().value());
    assertEquals(4, loot.rolls());
  }

  @Test
  void extremeZoneAndGamePopulationsAreRejectedWithoutClampingOrOverflow() {
    assertFalse(AiRuntimePolicy.capacity(List.of(
        new AiRuntimePolicy.ZoneCapacity("ten_thousand", 10_000))).allowed());
    assertFalse(AiRuntimePolicy.capacity(List.of(
        new AiRuntimePolicy.ZoneCapacity("integer_max", Integer.MAX_VALUE))).allowed());
    assertTrue(AiRuntimePolicy.capacity(List.of(
        new AiRuntimePolicy.ZoneCapacity("a", 32),
        new AiRuntimePolicy.ZoneCapacity("b", 32),
        new AiRuntimePolicy.ZoneCapacity("c", 32),
        new AiRuntimePolicy.ZoneCapacity("d", 32))).allowed());
    assertFalse(AiRuntimePolicy.capacity(List.of(
        new AiRuntimePolicy.ZoneCapacity("a", 32),
        new AiRuntimePolicy.ZoneCapacity("b", 32),
        new AiRuntimePolicy.ZoneCapacity("c", 32),
        new AiRuntimePolicy.ZoneCapacity("d", 32),
        new AiRuntimePolicy.ZoneCapacity("e", 1))).allowed());
    assertEquals(4, AiRuntimePolicy.MAXIMUM_SPAWN_ATTEMPTS_PER_TICK);
  }
}
