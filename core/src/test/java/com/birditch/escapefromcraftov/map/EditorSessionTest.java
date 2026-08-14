package com.birditch.escapefromcraftov.map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EditorSessionTest {

  @Test
  void keepsEachAdministratorsDraftIsolated() {
    EditorSessionService sessions = new EditorSessionService();
    EditorSession first = sessions.enter(UUID.randomUUID(), ManualMapFixtures.validMap());
    EditorSession second = sessions.enter(UUID.randomUUID(), ManualMapFixtures.validMap());

    first.apply(map -> map.withBounds(new CuboidBounds(0, 0, 0, 600, 100, 600)));

    assertTrue(first.dirty());
    assertFalse(second.dirty());
    assertNotSame(first.draft(), second.draft());
  }

  @Test
  void refusesToDiscardDirtySessionSilently() {
    EditorSessionService sessions = new EditorSessionService();
    UUID player = UUID.randomUUID();
    sessions.enter(player, ManualMapFixtures.validMap()).apply(map -> map.withEnabled(false));

    assertThrows(IllegalStateException.class, () -> sessions.exit(player, false));
    assertTrue(sessions.exit(player, true).isPresent());
  }

  @Test
  void resumesDirtyDraftForTheSameMapButCannotSwitchMaps() {
    EditorSessionService sessions = new EditorSessionService();
    UUID player = UUID.randomUUID();
    EditorSession original = sessions.enter(player, ManualMapFixtures.validMap());
    original.apply(map -> map.withEnabled(false));

    EditorSession resumed = sessions.enter(player, ManualMapFixtures.validMap());

    assertSame(original, resumed);
    assertTrue(resumed.dirty());
    ManualCraftovMap other = ManualCraftovMap.create("other", "other_world");
    assertThrows(IllegalStateException.class, () -> sessions.enter(player, other));
  }
}
