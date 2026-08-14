package com.birditch.escapefromcraftov.api.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RaidActionProfileTest {

  @Test
  void defaultProfilesExposeOnlyThreeCanonicalTiersAndExactLegacyAliases() {
    List<RaidActionProfile> profiles = LegacyRaidTierResolver.defaultProfiles();

    assertEquals(List.of("normal", "confidential", "top_secret"),
        profiles.stream().map(profile -> profile.id().value()).toList());
    assertEquals("normal", LegacyRaidTierResolver.resolve(
        profiles, RaidMode.NORMAL, RaidDifficulty.NORMAL).orElseThrow().id().value());
    assertEquals("confidential", LegacyRaidTierResolver.resolve(
        profiles, RaidMode.CONFIDENTIAL, RaidDifficulty.NORMAL).orElseThrow().id().value());
    assertEquals("top_secret", LegacyRaidTierResolver.resolve(
        profiles, RaidMode.CONFIDENTIAL, RaidDifficulty.HARD).orElseThrow().id().value());
    assertTrue(LegacyRaidTierResolver.resolve(
        profiles, RaidMode.NORMAL, RaidDifficulty.EASY).isEmpty());
  }

  @Test
  void validatesIdentifiersEconomicGatesAndMultipliers() {
    assertThrows(IllegalArgumentException.class, () -> RaidTierId.of("Top Secret"));
    assertThrows(IllegalArgumentException.class, () -> profile(-1, 1, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> profile(0, 0, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> profile(
        0, RaidActionProfile.MAX_AI_MULTIPLIER + 0.01, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> profile(0, 1, Double.NaN, 0));
    assertThrows(IllegalArgumentException.class, () -> profile(
        0, 1, RaidActionProfile.MAX_LOOT_MULTIPLIER + 0.01, 0));
    assertThrows(IllegalArgumentException.class, () -> profile(0, 1, 1, -1));
  }

  private static RaidActionProfile profile(long minimumLoadoutValue, double aiMultiplier,
      double lootMultiplier, long entryFee) {
    return new RaidActionProfile(RaidTierId.of("normal"), "常规行动", minimumLoadoutValue,
        aiMultiplier, lootMultiplier, entryFee, List.of());
  }
}
