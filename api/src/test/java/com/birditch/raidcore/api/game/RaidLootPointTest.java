package com.birditch.raidcore.api.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.loot.LootContainerType;
import com.birditch.raidcore.api.loot.LootTableId;
import org.junit.jupiter.api.Test;

class RaidLootPointTest {
  @Test
  void capturesImmutablePointAndDistance() {
    RaidLootPoint point = new RaidLootPoint("warehouse", 10, 64, -4,
        LootContainerType.CHEST, LootTableId.of("debug_common"), 2, true);

    assertEquals(2, point.tier());
    assertTrue(point.distanceSquared(10.5, 64.5, -3.5) < 0.01);
  }
}
