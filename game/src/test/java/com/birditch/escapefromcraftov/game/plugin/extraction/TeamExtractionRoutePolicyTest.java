package com.birditch.escapefromcraftov.game.plugin.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.game.CraftovExtractionArea;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TeamExtractionRoutePolicyTest {

  private static final ExtractionPointId WEST = ExtractionPointId.of("west");
  private static final ExtractionPointId EAST = ExtractionPointId.of("east");
  private static final ExtractionPointId ORPHAN = ExtractionPointId.of("orphan");

  @Test
  void choosesFarthestEnabledExitInTheHorizontalPlane() {
    List<CraftovExtractionArea> areas = List.of(
        area(WEST, -110, -100, 2_000),
        area(EAST, 90, 100, -2_000),
        area(ORPHAN, 1_000, 1_010, 0));

    assertEquals(EAST, TeamExtractionRoutePolicy.farthest(
        areas, Set.of(WEST, EAST), -80, 0, "game:1").orElseThrow());
  }

  @Test
  void symmetricTieIsStableAndEmptySpatialDataDoesNotInventARoute() {
    List<CraftovExtractionArea> symmetric = List.of(
        area(WEST, -10, -10, 0), area(EAST, 10, 10, 0));

    var first = TeamExtractionRoutePolicy.farthest(
        symmetric, Set.of(WEST, EAST), 0, 0, "same-team");
    var second = TeamExtractionRoutePolicy.farthest(
        symmetric.reversed(), Set.of(WEST, EAST), 0, 0, "same-team");

    assertEquals(first, second);
    assertTrue(TeamExtractionRoutePolicy.farthest(
        List.of(), Set.of(WEST), 0, 0, "legacy").isEmpty());
  }

  private static CraftovExtractionArea area(
      ExtractionPointId id, int minX, int maxX, int y) {
    return new CraftovExtractionArea(id, id.value(), minX, y, -1, maxX, y + 10, 1);
  }
}
