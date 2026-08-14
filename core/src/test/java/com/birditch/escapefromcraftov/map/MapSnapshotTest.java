package com.birditch.escapefromcraftov.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    assertEquals(1, edited.extractions().size());
    assertThrows(UnsupportedOperationException.class, snapshot.extractions()::clear);
    assertThrows(UnsupportedOperationException.class, snapshot.actionTiers()::clear);
  }
}
