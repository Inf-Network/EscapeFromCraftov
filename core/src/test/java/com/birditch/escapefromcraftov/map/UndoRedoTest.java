package com.birditch.escapefromcraftov.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;

class UndoRedoTest {

  @Test
  void clearsRedoAfterNewEditAndRetainsAtMostOneHundredSnapshots() {
    EditorSession session = new EditorSession(UUID.randomUUID(), ManualMapFixtures.validMap());
    for (int index = 0; index < 105; index++) {
      int edge = 501 + index;
      session.apply(map -> map.withBounds(new CuboidBounds(0, 0, 0, edge, 100, edge)));
    }

    int undoCount = 0;
    while (session.undo()) undoCount++;
    assertEquals(100, undoCount);
    assertTrue(session.redo());
    session.apply(map -> map.withEnabled(false));
    assertFalse(session.canRedo());
  }

  @Test
  void invalidatesValidationWheneverDraftChanges() {
    EditorSession session = new EditorSession(UUID.randomUUID(), ManualMapFixtures.validMap());
    session.setValidationResult(new ManualMapValidationResult(List.of(), 100));

    session.apply(map -> map.withEnabled(false));
    assertTrue(session.validationResult().isEmpty());

    session.setValidationResult(new ManualMapValidationResult(List.of(), 100));
    session.undo();
    assertTrue(session.validationResult().isEmpty());
  }
}
