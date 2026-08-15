package com.birditch.escapefromcraftov.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiPatrolRouteValidationTest {

  @Test
  void validatesDuplicatesAndBothSpatialBoundaries() {
    ManualCraftovMap source = ManualMapFixtures.validMap();
    AiZoneDefinition original = source.aiZones().getFirst();
    AiZoneDefinition invalid = new AiZoneDefinition(original.id(), original.bounds(),
        original.aiType(), original.minimum(), original.maximum(), original.respawnSeconds(),
        original.difficulty(), original.team(), original.enabled(), List.of(
            new BlockPosition(220, 64, 220),
            new BlockPosition(220, 64, 220),
            new BlockPosition(200, 64, 220),
            new BlockPosition(600, 64, 600)));
    ManualCraftovMap map = new ManualMapEditor().replace(source, invalid);

    ManualMapValidationResult result = new ManualMapValidator().validate(map,
        ManualMapFixtures.references(), ManualMapValidationSettings.defaults());

    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("ai_patrol_duplicate")));
    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("ai_patrol_outside_zone")));
    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("ai_patrol_outside_bounds")));
  }

  @Test
  void domainRejectsRoutesAboveTheBound() {
    AiZoneDefinition original = ManualMapFixtures.validMap().aiZones().getFirst();
    List<BlockPosition> route = java.util.stream.IntStream.rangeClosed(
        0, CraftovAiZone.MAXIMUM_PATROL_WAYPOINTS)
        .mapToObj(index -> new BlockPosition(220 + index, 64, 220)).toList();

    assertThrows(IllegalArgumentException.class, () -> new AiZoneDefinition(original.id(),
        original.bounds(), original.aiType(), original.minimum(), original.maximum(),
        original.respawnSeconds(), original.difficulty(), original.team(), original.enabled(),
        route));
  }

  @Test
  void movingAndCloningAnAiZoneTranslateItsWholeRoute() {
    ManualMapEditor editor = new ManualMapEditor();
    ManualCraftovMap source = ManualMapFixtures.validMap().withEvents(List.of());
    AiZoneDefinition original = source.aiZones().getFirst();
    source = editor.replace(source, new AiZoneDefinition(original.id(), original.bounds(),
        original.aiType(), original.minimum(), original.maximum(), original.respawnSeconds(),
        original.difficulty(), original.team(), original.enabled(), List.of(
            new BlockPosition(220, 64, 220), new BlockPosition(280, 64, 280))));

    ManualCraftovMap moved = editor.move(source, original.id(), new BlockPosition(260, 67, 245));
    ManualCraftovMap cloned = editor.cloneAt(source, original.id(), "ai_zone_clone",
        new BlockPosition(240, 63, 270));

    assertEquals(List.of(new BlockPosition(230, 66, 215),
        new BlockPosition(290, 66, 275)), moved.aiZones().getFirst().patrolWaypoints());
    assertEquals(List.of(new BlockPosition(210, 62, 240),
        new BlockPosition(270, 62, 300)), cloned.findObject("ai_zone_clone")
            .map(AiZoneDefinition.class::cast).orElseThrow().patrolWaypoints());
  }

  @Test
  void moveRejectsAThreeAxisDeltaThatCannotFitAnInteger() {
    AiZoneDefinition zone = new AiZoneDefinition("edge_zone",
        new CuboidBounds(Integer.MIN_VALUE, 0, 0, Integer.MIN_VALUE + 2, 2, 2),
        "scav", 0, 0, 0, 1, "scav", true,
        List.of(new BlockPosition(Integer.MIN_VALUE + 1, 1, 1)));
    ManualCraftovMap map = ManualCraftovMap.create("edge", "world")
        .withObjects(List.of(), List.of(), List.of(), List.of(zone), List.of(), List.of());

    assertThrows(ArithmeticException.class, () -> new ManualMapEditor().move(
        map, zone.id(), new BlockPosition(Integer.MAX_VALUE, 1, 1)));
  }
}
