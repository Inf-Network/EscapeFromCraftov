package com.birditch.escapefromcraftov.game.plugin.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class AiTacticsPolicyTest {

  @Test
  void followsTheWakeChaseSearchAndReturnLifecycle() {
    assertEquals(AiTacticsPolicy.State.PATROL, next(
        AiTacticsPolicy.State.DORMANT, true, false, false, false,
        true, true, false, Duration.ZERO, Duration.ZERO));
    assertEquals(AiTacticsPolicy.State.ALERT, next(
        AiTacticsPolicy.State.PATROL, true, true, false, false,
        true, true, false, Duration.ZERO, Duration.ZERO));
    assertEquals(AiTacticsPolicy.State.CHASE, next(
        AiTacticsPolicy.State.ALERT, true, false, true, true,
        true, true, false, Duration.ZERO, Duration.ZERO));
    assertEquals(AiTacticsPolicy.State.SEARCH, next(
        AiTacticsPolicy.State.ALERT, true, false, true, false,
        true, true, false, Duration.ZERO, Duration.ZERO));
    assertEquals(AiTacticsPolicy.State.CHASE, next(
        AiTacticsPolicy.State.CHASE, true, false, true, false,
        true, true, false, Duration.ofSeconds(2), Duration.ofSeconds(5)));
    assertEquals(AiTacticsPolicy.State.SEARCH, next(
        AiTacticsPolicy.State.CHASE, true, false, true, false,
        true, true, false, Duration.ofSeconds(8), Duration.ofSeconds(6)));
    assertEquals(AiTacticsPolicy.State.RETURN, next(
        AiTacticsPolicy.State.SEARCH, true, false, false, false,
        false, true, false, Duration.ofSeconds(8), Duration.ofSeconds(8)));
    assertEquals(AiTacticsPolicy.State.PATROL, next(
        AiTacticsPolicy.State.RETURN, true, false, false, false,
        false, true, true, Duration.ofSeconds(2), Duration.ofSeconds(10)));
  }

  @Test
  void sleepsWithoutParticipantsAndLeashViolationsAlwaysReturn() {
    assertEquals(AiTacticsPolicy.State.DORMANT, next(
        AiTacticsPolicy.State.PATROL, false, false, false, false,
        false, true, false, Duration.ZERO, Duration.ZERO));
    assertEquals(AiTacticsPolicy.State.RETURN, next(
        AiTacticsPolicy.State.CHASE, true, true, true, true,
        true, false, false, Duration.ZERO, Duration.ZERO));
  }

  @Test
  void zoneLeashIncludesItsMarginButRejectsTargetsBeyondIt() {
    assertTrue(AiTacticsPolicy.insideLeash(125, 50, 0, 100, 0, 100, 24));
    assertFalse(AiTacticsPolicy.insideLeash(125.01, 50, 0, 100, 0, 100, 24));
  }

  private static AiTacticsPolicy.State next(AiTacticsPolicy.State state,
      boolean participantNearby, boolean acquiredTarget, boolean targetValid,
      boolean targetVisible, boolean targetInsideLeash, boolean actorInsideLeash,
      boolean homeReached, Duration stateAge, Duration sinceLastSeen) {
    return AiTacticsPolicy.next(new AiTacticsPolicy.Input(state, participantNearby,
        acquiredTarget, targetValid, targetVisible, targetInsideLeash, actorInsideLeash,
        homeReached, stateAge, sinceLastSeen), AiTacticsPolicy.DEFAULT);
  }
}
