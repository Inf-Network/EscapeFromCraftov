package com.birditch.escapefromcraftov.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MapRevisionTest {

  @Test
  void savedRevisionBecomesNewCleanBaseline() {
    EditorSession session = new EditorSession(UUID.randomUUID(), ManualMapFixtures.validMap());
    session.apply(map -> map.withEnabled(false));
    ManualCraftovMap persisted = session.draft().withRevision(5, false);

    session.markSaved(persisted);

    assertFalse(session.dirty());
    assertFalse(session.canUndo());
    assertFalse(session.canRedo());
    assertEquals(5, session.draft().revision());
    session.apply(map -> map.withEnabled(true));
    assertTrue(session.dirty());
  }

  @Test
  void saveDropsHistoryThatCarriesThePreviousRevision() {
    EditorSession session = new EditorSession(UUID.randomUUID(), ManualMapFixtures.validMap());
    session.apply(map -> map.withEnabled(false));
    session.undo();
    session.redo();

    ManualCraftovMap persisted = session.draft().withRevision(7, false);
    session.markSaved(persisted);

    assertFalse(session.undo());
    assertFalse(session.redo());
    assertEquals(7, session.draft().revision());
  }
}
