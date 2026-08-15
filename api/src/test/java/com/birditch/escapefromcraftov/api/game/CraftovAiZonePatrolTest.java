package com.birditch.escapefromcraftov.api.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CraftovAiZonePatrolTest {

  @Test
  void compatibilityConstructorKeepsAnEmptyPatrolRoute() {
    CraftovAiZone zone = new CraftovAiZone(
        "guards", "scav", 1, 2, 30, 1, "scav", 0, 60, 0, 10, 70, 10);

    assertEquals(List.of(), zone.patrolWaypoints());
  }

  @Test
  void copiesTheAuthoredOrderAndRejectsMutation() {
    ArrayList<CraftovAiWaypoint> source = new ArrayList<>(List.of(
        new CraftovAiWaypoint(1, 64, 1), new CraftovAiWaypoint(9, 64, 9)));
    CraftovAiZone zone = zone(source);

    source.clear();

    assertEquals(List.of(new CraftovAiWaypoint(1, 64, 1),
        new CraftovAiWaypoint(9, 64, 9)), zone.patrolWaypoints());
    assertThrows(UnsupportedOperationException.class,
        () -> zone.patrolWaypoints().add(new CraftovAiWaypoint(5, 64, 5)));
  }

  @Test
  void rejectsEveryAxisOutsideTheZoneAndDuplicatePoints() {
    assertThrows(IllegalArgumentException.class,
        () -> zone(List.of(new CraftovAiWaypoint(-1, 64, 1))));
    assertThrows(IllegalArgumentException.class,
        () -> zone(List.of(new CraftovAiWaypoint(1, 71, 1))));
    assertThrows(IllegalArgumentException.class,
        () -> zone(List.of(new CraftovAiWaypoint(1, 64, 11))));
    assertThrows(IllegalArgumentException.class, () -> zone(List.of(
        new CraftovAiWaypoint(1, 64, 1), new CraftovAiWaypoint(1, 64, 1))));
  }

  @Test
  void rejectsMoreThanTheRuntimeBudget() {
    List<CraftovAiWaypoint> route = java.util.stream.IntStream.rangeClosed(
        0, CraftovAiZone.MAXIMUM_PATROL_WAYPOINTS)
        .mapToObj(index -> new CraftovAiWaypoint(index, 64, 0)).toList();

    assertThrows(IllegalArgumentException.class, () -> new CraftovAiZone(
        "guards", "scav", 1, 2, 30, 1, "scav", 0, 60, 0, 64, 70, 10, route));
  }

  private static CraftovAiZone zone(List<CraftovAiWaypoint> route) {
    return new CraftovAiZone(
        "guards", "scav", 1, 2, 30, 1, "scav", 0, 60, 0, 10, 70, 10, route);
  }
}
