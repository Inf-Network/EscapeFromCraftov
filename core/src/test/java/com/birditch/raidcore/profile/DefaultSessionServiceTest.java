package com.birditch.raidcore.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.session.PlayerSession;
import com.birditch.raidcore.api.session.ProfileLoadState;
import com.birditch.raidcore.api.testing.MutableTimeSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultSessionService")
class DefaultSessionServiceTest {

  private final MutableTimeSource timeSource = new MutableTimeSource(5_000L);
  private final DefaultSessionService service = new DefaultSessionService(timeSource);
  private final UUID playerId = UUID.randomUUID();

  @Test
  @DisplayName("create stamps the session with the current time")
  void createStampsCurrentTime() {
    PlayerSession session = service.create(playerId, "Steve");
    assertEquals(playerId, session.playerId());
    assertEquals("Steve", session.playerName());
    assertEquals(Instant.ofEpochMilli(5_000L), session.createdAt());
    assertEquals(ProfileLoadState.LOADING, session.profileState());
    assertEquals(1, service.count());
  }

  @Test
  @DisplayName("create replaces a stale session with a fresh one")
  void createReplacesStaleSession() {
    PlayerSession stale = service.create(playerId, "Steve");
    stale.markProfileReady();
    PlayerSession fresh = service.create(playerId, "Steve");
    assertNotSame(stale, fresh);
    assertEquals(ProfileLoadState.LOADING, fresh.profileState());
    assertSame(fresh, service.find(playerId).orElseThrow());
    assertEquals(1, service.count());
  }

  @Test
  @DisplayName("find is empty for unknown players")
  void findEmptyForUnknown() {
    assertTrue(service.find(playerId).isEmpty());
  }

  @Test
  @DisplayName("end removes and returns the session")
  void endRemovesSession() {
    PlayerSession session = service.create(playerId, "Steve");
    assertSame(session, service.end(playerId).orElseThrow());
    assertTrue(service.find(playerId).isEmpty());
    assertEquals(0, service.count());
    assertTrue(service.end(playerId).isEmpty());
  }

  @Test
  @DisplayName("all returns an unmodifiable snapshot in creation order")
  void allReturnsUnmodifiableSnapshotInOrder() {
    UUID secondId = UUID.randomUUID();
    PlayerSession first = service.create(playerId, "Steve");
    PlayerSession second = service.create(secondId, "Alex");
    assertEquals(List.of(first, second), List.copyOf(service.all()));
    assertThrows(UnsupportedOperationException.class, () -> service.all().clear());
  }
}
