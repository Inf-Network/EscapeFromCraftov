package com.birditch.escapefromcraftov.automap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LootGeneratorTest {

  @Test
  void createsRiskWeightedZonesAndUsesExistingContainers() {
    LootGenerator generator = new LootGenerator();
    var first = generator.generate(AutoMapFixtures.terrain(), AutoMapFixtures.BOUNDS,
        AutoMapFixtures.pois(), AutoMapFixtures.settings().loot(), 91L);
    var second = generator.generate(AutoMapFixtures.terrain(), AutoMapFixtures.BOUNDS,
        AutoMapFixtures.pois(), AutoMapFixtures.settings().loot(), 91L);

    assertEquals(first, second);
    assertEquals(AutoMapFixtures.pois().size(), first.zones().size());
    assertFalse(first.points().isEmpty());
    assertTrue(first.points().size() <= AutoMapFixtures.settings().loot().maximumPoints());
    assertTrue(first.points().stream().anyMatch(point -> !point.virtualContainer()));
  }
}
