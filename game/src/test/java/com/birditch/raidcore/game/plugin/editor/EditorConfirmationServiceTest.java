package com.birditch.raidcore.game.plugin.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EditorConfirmationServiceTest {

  @Test
  void runsActionOnceInsideWindowAndExpiresAfterFifteenSeconds() {
    MutableClock clock = new MutableClock();
    EditorConfirmationService service = new EditorConfirmationService(clock);
    AtomicInteger calls = new AtomicInteger();
    UUID player = UUID.randomUUID();
    service.request(player, "delete", calls::incrementAndGet);

    assertEquals("delete", service.confirm(player).orElseThrow());
    assertEquals(1, calls.get());
    assertTrue(service.confirm(player).isEmpty());

    service.request(player, "delete again", calls::incrementAndGet);
    clock.now = clock.now.plusSeconds(16);
    assertTrue(service.confirm(player).isEmpty());
    assertEquals(1, calls.get());
  }

  @Test
  void cancelledActionCannotRunInALaterEditorSession() {
    EditorConfirmationService service = new EditorConfirmationService(new MutableClock());
    AtomicInteger calls = new AtomicInteger();
    UUID player = UUID.randomUUID();
    service.request(player, "delete", calls::incrementAndGet);

    service.cancel(player);

    assertTrue(service.confirm(player).isEmpty());
    assertEquals(0, calls.get());
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.EPOCH;

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
}
