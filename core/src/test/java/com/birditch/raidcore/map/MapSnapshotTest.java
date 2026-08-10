package com.birditch.raidcore.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MapSnapshotTest {

  @Test
  void capturedRevisionDoesNotChangeWithLaterEdits() {
    ManualRaidMap original = ManualMapFixtures.validMap();
    RaidMapSnapshot snapshot = RaidMapSnapshot.capture(original);
    ManualRaidMap edited = new ManualMapEditor().remove(original, "north_gate")
        .withRevision(5, true);

    assertEquals(4, snapshot.revision());
    assertEquals(2, snapshot.extractions().size());
    assertEquals(1, edited.extractions().size());
    assertThrows(UnsupportedOperationException.class, snapshot.extractions()::clear);
  }
}
