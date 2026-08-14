package com.birditch.escapefromcraftov.automap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpawnGeneratorTest {

  @Test
  void producesSeparatedSafeEdgeRegionsDeterministically() {
    List<TerrainSample> terrain = AutoMapFixtures.terrain();
    AutoSetupSettings.SpawnSettings settings = AutoMapFixtures.settings().spawns();
    SpawnGenerator generator = new SpawnGenerator();
    List<SpawnRegion> first = generator.generate(terrain, AutoMapFixtures.BOUNDS,
        AutoMapFixtures.pois(), settings, 42L);
    List<SpawnRegion> second = generator.generate(terrain, AutoMapFixtures.BOUNDS,
        AutoMapFixtures.pois(), settings, 42L);

    assertEquals(first, second);
    assertTrue(first.size() >= settings.minimum());
    for (int i = 0; i < first.size(); i++) {
      for (int j = i + 1; j < first.size(); j++) {
        assertTrue(first.get(i).center().horizontalDistance(first.get(j).center())
            >= settings.minimumDistance());
      }
      assertTrue(AutoMapFixtures.BOUNDS.edgeDistance(first.get(i).center().x(),
          first.get(i).center().z()) <= 64);
    }
  }
}
