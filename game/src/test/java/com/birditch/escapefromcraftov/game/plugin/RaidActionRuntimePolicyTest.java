package com.birditch.escapefromcraftov.game.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import java.util.List;
import org.junit.jupiter.api.Test;

class RaidActionRuntimePolicyTest {

  @Test
  void legacyRoomKeepsExactModeAndDifficultyRules() {
    assertEquals(1.875, RaidActionRuntimePolicy.aiMultiplier(
        RaidMode.CONFIDENTIAL, RaidDifficulty.HARD, null));
    for (long seed = 0; seed < 20; seed++) {
      assertEquals(3, RaidActionRuntimePolicy.lootRolls(
          RaidMode.CONFIDENTIAL, RaidDifficulty.HARD, null, seed));
    }
  }

  @Test
  void canonicalProfileReplacesRatherThanDoubleCountsLegacyRules() {
    RaidActionProfile profile = profile(1.4, 2.0);
    assertEquals(1.4, RaidActionRuntimePolicy.aiMultiplier(
        RaidMode.CONFIDENTIAL, RaidDifficulty.HARD, profile));
    assertEquals(2, RaidActionRuntimePolicy.lootRolls(
        RaidMode.CONFIDENTIAL, RaidDifficulty.HARD, profile, 17));
  }

  @Test
  void fractionalLootExpectationUsesStableSourceRounding() {
    RaidActionProfile profile = profile(1.0, 1.5);
    CraftovGameId gameId = CraftovGameId.of("room-42");
    long firstSeed = RaidActionRuntimePolicy.sourceSeed(gameId, "container:loot-a");
    assertEquals(firstSeed,
        RaidActionRuntimePolicy.sourceSeed(gameId, "container:loot-a"));
    int first = RaidActionRuntimePolicy.lootRolls(
        RaidMode.NORMAL, RaidDifficulty.NORMAL, profile, firstSeed);
    assertEquals(first, RaidActionRuntimePolicy.lootRolls(
        RaidMode.NORMAL, RaidDifficulty.NORMAL, profile, firstSeed));

    int total = 0;
    for (int index = 0; index < 10_000; index++) {
      total += RaidActionRuntimePolicy.lootRolls(RaidMode.NORMAL, RaidDifficulty.NORMAL,
          profile, RaidActionRuntimePolicy.sourceSeed(gameId, "container:" + index));
    }
    assertTrue(total > 14_700 && total < 15_300,
        "deterministic source distribution should preserve the authored expectation");
  }

  private static RaidActionProfile profile(double aiMultiplier, double lootMultiplier) {
    return new RaidActionProfile(RaidTierId.of("custom"), "自定义", 0,
        aiMultiplier, lootMultiplier, 0, List.of());
  }
}
