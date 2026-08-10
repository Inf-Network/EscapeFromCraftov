package com.birditch.raidcore.api.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RaidSpawnTeamRulesTest {

  @Test
  void mapsGenericNamedAndNumericTeamLabels() {
    assertTrue(RaidSpawnTeamRules.accepts("players", 8));
    assertTrue(RaidSpawnTeamRules.accepts("any", 3));
    assertTrue(RaidSpawnTeamRules.accepts("red", 1));
    assertTrue(RaidSpawnTeamRules.accepts("blue", 2));
    assertTrue(RaidSpawnTeamRules.accepts("team_7", 7));
    assertTrue(RaidSpawnTeamRules.accepts("team-4", 4));
    assertFalse(RaidSpawnTeamRules.accepts("red", 2));
    assertFalse(RaidSpawnTeamRules.isValid("builders"));
  }
}
