package com.birditch.escapefromcraftov.game.plugin.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FriendlyAttributionCandidatesTest {

  private static final CraftovGameId GAME = CraftovGameId.of("friendly-test");

  @Test
  void lethalFriendlyHitThatSynchronouslyDownsVictimConfirmsHostileClear() {
    FriendlyAttributionCandidates<Object> candidates = new FriendlyAttributionCandidates<>();
    UUID victim = UUID.randomUUID();
    Object event = new Object();

    candidates.begin(victim, GAME, event);

    assertTrue(candidates.confirmsDowned(victim, GAME));
    assertFalse(candidates.finish(event, true, 8),
        "cancelled down conversion is confirmed by PlayerDownedEvent, not MONITOR");
    assertFalse(candidates.pending(victim));
  }

  @Test
  void cancelledFriendlyTouchOnAlreadyDownedVictimHasNoCandidateAndKeepsHostileEvidence() {
    FriendlyAttributionCandidates<Object> candidates = new FriendlyAttributionCandidates<>();
    UUID victim = UUID.randomUUID();
    Object event = new Object();

    assertFalse(candidates.confirmsDowned(victim, GAME));
    assertFalse(candidates.finish(event, true, 1));
    assertFalse(candidates.pending(victim));
  }

  @Test
  void monitorClearsEffectiveHitAndAlwaysDisposesCancelledCandidate() {
    FriendlyAttributionCandidates<Object> candidates = new FriendlyAttributionCandidates<>();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Object effective = new Object();
    Object cancelled = new Object();
    candidates.begin(first, GAME, effective);
    candidates.begin(second, GAME, cancelled);

    assertTrue(candidates.finish(effective, false, 2));
    assertFalse(candidates.finish(cancelled, true, 2));
    assertFalse(candidates.pending(first));
    assertFalse(candidates.pending(second));
  }

  @Test
  void nestedEventsRetainTheOuterVictimCandidateUntilItsOwnMonitor() {
    FriendlyAttributionCandidates<Object> candidates = new FriendlyAttributionCandidates<>();
    UUID victim = UUID.randomUUID();
    Object outer = new Object();
    Object inner = new Object();
    candidates.begin(victim, GAME, outer);
    candidates.begin(victim, GAME, inner);

    assertFalse(candidates.finish(inner, true, 1));
    assertTrue(candidates.confirmsDowned(victim, GAME));
    assertFalse(candidates.finish(outer, true, 1));
    assertFalse(candidates.pending(victim));
  }
}
