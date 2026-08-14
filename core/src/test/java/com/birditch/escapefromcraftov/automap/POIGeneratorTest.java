package com.birditch.escapefromcraftov.automap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class POIGeneratorTest {

  @Test
  void detectsStructureAndFillsConfiguredMinimumDeterministically() {
    POIGenerator generator = new POIGenerator();
    List<PointOfInterest> first = generator.generate(AutoMapFixtures.terrain(),
        AutoMapFixtures.BOUNDS, AutoMapFixtures.STEP, AutoMapFixtures.settings().poi());
    List<PointOfInterest> second = generator.generate(AutoMapFixtures.terrain(),
        AutoMapFixtures.BOUNDS, AutoMapFixtures.STEP, AutoMapFixtures.settings().poi());

    assertEquals(first, second);
    assertTrue(first.size() >= AutoMapFixtures.settings().poi().minimum());
    assertTrue(first.stream().anyMatch(poi -> poi.score() > 0.5));
  }
}
