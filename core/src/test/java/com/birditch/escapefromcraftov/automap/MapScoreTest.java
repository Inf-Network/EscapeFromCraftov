package com.birditch.escapefromcraftov.automap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MapScoreTest {

  @Test
  void rewardsDistributionAcrossQuadrants() {
    MapBounds bounds = MapBounds.around(0, 0, 200);
    List<SpawnRegion> clustered = List.of(
        new SpawnRegion("s1", new MapPoint(100, 64, 100), 8),
        new SpawnRegion("s2", new MapPoint(120, 64, 120), 8));
    List<SpawnRegion> spread = List.of(
        new SpawnRegion("s1", new MapPoint(-100, 64, -100), 8),
        new SpawnRegion("s2", new MapPoint(100, 64, 100), 8));
    MapScore score = new MapScore();

    int clusteredScore = score.calculate(bounds, clustered, List.of(), List.of(), List.of(),
        List.of());
    int spreadScore = score.calculate(bounds, spread, List.of(), List.of(), List.of(), List.of());

    assertTrue(spreadScore > clusteredScore);
  }
}
