package com.birditch.raidcore.automap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractionGeneratorTest {

  @Test
  void spreadsNormalAndConditionalExitsAwayFromSpawns() {
    var settings = AutoMapFixtures.settings().extractions();
    List<SpawnRegion> spawns = List.of(new SpawnRegion("spawn", new MapPoint(0, 65, 0), 8));
    ExtractionGenerator generator = new ExtractionGenerator();
    List<ExtractionRegion> first = generator.generate(AutoMapFixtures.terrain(),
        AutoMapFixtures.BOUNDS, spawns, settings, 71L);
    List<ExtractionRegion> second = generator.generate(AutoMapFixtures.terrain(),
        AutoMapFixtures.BOUNDS, spawns, settings, 71L);

    assertEquals(first, second);
    assertTrue(first.size() >= settings.normalMinimum() + settings.conditionalMinimum());
    assertTrue(first.stream().filter(exit -> exit.type() == AutoExtractionType.NORMAL).count()
        >= settings.normalMinimum());
    assertTrue(first.stream().allMatch(exit -> exit.center().horizontalDistance(
        spawns.getFirst().center()) >= settings.minimumSpawnDistance()));
    assertTrue(first.stream().map(exit -> direction(exit.center())).distinct().count() >= 3);
  }

  private static int direction(MapPoint point) {
    return Math.abs(point.x()) > Math.abs(point.z()) ? (point.x() > 0 ? 1 : 3)
        : (point.z() > 0 ? 2 : 0);
  }
}
