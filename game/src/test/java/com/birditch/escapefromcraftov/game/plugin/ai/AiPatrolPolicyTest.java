package com.birditch.escapefromcraftov.game.plugin.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovAiWaypoint;
import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiPatrolPolicyTest {

  private static final List<CraftovAiWaypoint> ROUTE = List.of(
      new CraftovAiWaypoint(0, 64, 0),
      new CraftovAiWaypoint(10, 64, 0),
      new CraftovAiWaypoint(10, 64, 10),
      new CraftovAiWaypoint(0, 64, 10));

  @Test
  void stableSpawnOrdinalDispersesDifferentHomesFromOneSharedZoneReference() {
    CraftovAiZone zone = zone();

    List<Integer> firstCycle = java.util.stream.LongStream.range(0, ROUTE.size())
        .mapToInt(ordinal -> AiPatrolPolicy.initialCursor(zone, ordinal)).boxed().toList();

    assertEquals(List.of(0, 1, 2, 3), firstCycle);
    assertEquals(ROUTE.size(), new java.util.HashSet<>(firstCycle).size());
    assertEquals(firstCycle.getFirst(), AiPatrolPolicy.initialCursor(zone, ROUTE.size()));
  }

  @Test
  void onlyPhysicalArrivalAdvancesTheAuthoredCursor() {
    for (AiPatrolPolicy.Progress progress : List.of(
        AiPatrolPolicy.Progress.PATH_STARTED,
        AiPatrolPolicy.Progress.PATH_FAILED,
        AiPatrolPolicy.Progress.UNLOADED,
        AiPatrolPolicy.Progress.DEFERRED,
        AiPatrolPolicy.Progress.BUDGET_EXHAUSTED)) {
      assertEquals(2, AiPatrolPolicy.cursorAfter(2, ROUTE.size(), progress), progress.name());
    }
    assertEquals(3, AiPatrolPolicy.cursorAfter(
        2, ROUTE.size(), AiPatrolPolicy.Progress.ARRIVED));
    assertEquals(0, AiPatrolPolicy.cursorAfter(
        3, ROUTE.size(), AiPatrolPolicy.Progress.ARRIVED));
  }

  @Test
  void emptyRoutesPreserveLegacyRandomPatrolAndRuntimeAcceptsBoundedRoutes() {
    CraftovAiZone empty = new CraftovAiZone(
        "empty", "scav", 1, 4, 60, 1, "scav", 0, 60, 0, 10, 70, 10);
    assertEquals(-1, AiPatrolPolicy.initialCursor(empty, 42));
    assertTrue(AiPatrolPolicy.validRoute(zone()));
  }

  private static CraftovAiZone zone() {
    return new CraftovAiZone(
        "guards", "scav", 1, 4, 60, 1, "scav", 0, 60, 0, 10, 70, 10, ROUTE);
  }
}
