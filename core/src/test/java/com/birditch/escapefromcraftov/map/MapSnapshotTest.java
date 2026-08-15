package com.birditch.escapefromcraftov.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class MapSnapshotTest {

  @Test
  void capturedRevisionDoesNotChangeWithLaterEdits() {
    ManualCraftovMap original = ManualMapFixtures.validMap();
    CraftovMapSnapshot snapshot = CraftovMapSnapshot.capture(original);
    ManualCraftovMap edited = new ManualMapEditor().remove(original, "north_gate")
        .withRevision(5, true);

    assertEquals(4, snapshot.revision());
    assertEquals(2, snapshot.extractions().size());
    assertEquals(original.actionTiers(), snapshot.actionTiers());
    assertEquals(original.events(), snapshot.events());
    assertEquals(1, edited.extractions().size());
    assertThrows(UnsupportedOperationException.class, snapshot.extractions()::clear);
    assertThrows(UnsupportedOperationException.class, snapshot.actionTiers()::clear);
    assertThrows(UnsupportedOperationException.class, snapshot.events()::clear);
  }

  @Test
  void capturedAiPatrolOrderIsFrozenWithTheRevision() {
    ManualMapEditor editor = new ManualMapEditor();
    ManualCraftovMap original = ManualMapFixtures.validMap();
    AiZoneDefinition zone = original.aiZones().getFirst();
    original = editor.replace(original, new AiZoneDefinition(zone.id(), zone.bounds(),
        zone.aiType(), zone.minimum(), zone.maximum(), zone.respawnSeconds(), zone.difficulty(),
        zone.team(), zone.enabled(), List.of(new BlockPosition(220, 64, 220),
            new BlockPosition(280, 64, 280))));

    CraftovMapSnapshot snapshot = CraftovMapSnapshot.capture(original);
    ManualCraftovMap edited = editor.replace(original, new AiZoneDefinition(zone.id(),
        zone.bounds(), zone.aiType(), zone.minimum(), zone.maximum(), zone.respawnSeconds(),
        zone.difficulty(), zone.team(), zone.enabled(), List.of(new BlockPosition(250, 64, 250))));

    assertEquals(List.of(new BlockPosition(220, 64, 220),
        new BlockPosition(280, 64, 280)), snapshot.aiZones().getFirst().patrolWaypoints());
    assertEquals(List.of(new BlockPosition(250, 64, 250)),
        edited.aiZones().getFirst().patrolWaypoints());
    assertThrows(UnsupportedOperationException.class,
        () -> snapshot.aiZones().getFirst().patrolWaypoints().clear());
  }
}
