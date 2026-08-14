package com.birditch.escapefromcraftov.api.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CraftovSpawnTeamRulesTest {

  @Test
  void mapsGenericNamedAndNumericTeamLabels() {
    assertTrue(CraftovSpawnTeamRules.accepts("players", 8));
    assertTrue(CraftovSpawnTeamRules.accepts("any", 3));
    assertTrue(CraftovSpawnTeamRules.accepts("red", 1));
    assertTrue(CraftovSpawnTeamRules.accepts("blue", 2));
    assertTrue(CraftovSpawnTeamRules.accepts("team_7", 7));
    assertTrue(CraftovSpawnTeamRules.accepts("team-4", 4));
    assertFalse(CraftovSpawnTeamRules.accepts("red", 2));
    assertFalse(CraftovSpawnTeamRules.isValid("builders"));
  }
}
