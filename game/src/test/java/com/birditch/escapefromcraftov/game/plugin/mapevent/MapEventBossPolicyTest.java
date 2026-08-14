package com.birditch.escapefromcraftov.game.plugin.mapevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovMapEvent;
import com.birditch.escapefromcraftov.api.game.CraftovMapEventType;
import org.junit.jupiter.api.Test;

class MapEventBossPolicyTest {

  private static final CraftovMapEvent EVENT = new CraftovMapEvent(
      "boss", "Boss", CraftovMapEventType.HIGH_VALUE_TARGET,
      "ai", "cache", 0, 4, 3, 30, true);

  @Test
  void pdcMustMatchEventIdentityAndBothFiniteMultipliers() {
    assertTrue(MapEventBossPolicy.matchesPdc(EVENT, "boss", 4D, 3D));
    assertFalse(MapEventBossPolicy.matchesPdc(EVENT, "other", 4D, 3D));
    assertFalse(MapEventBossPolicy.matchesPdc(EVENT, "boss", 5D, 3D));
    assertFalse(MapEventBossPolicy.matchesPdc(EVENT, "boss", 4D, Double.NaN));
  }

  @Test
  void meleeAndProjectileChannelsEachApplyBossDamageExactlyOnce() {
    assertEquals(15, MapEventBossPolicy.scaleDamage(5, EVENT).orElseThrow());
    assertEquals(4.5,
        MapEventBossPolicy.projectileMultiplier(1.5, EVENT).orElseThrow());
  }
}
