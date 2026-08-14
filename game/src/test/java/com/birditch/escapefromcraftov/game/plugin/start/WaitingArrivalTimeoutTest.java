package com.birditch.escapefromcraftov.game.plugin.start;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import org.junit.jupiter.api.Test;

class WaitingArrivalTimeoutTest {

  @Test
  void waitsOnlyForAPartiallyArrivedWaitingRoom() {
    assertEquals(WaitingArrivalTimeout.Decision.WAIT,
        WaitingArrivalTimeout.decision(CraftovGameState.WAITING, 1, 2));
    assertEquals(WaitingArrivalTimeout.Decision.READY,
        WaitingArrivalTimeout.decision(CraftovGameState.WAITING, 2, 2));
  }

  @Test
  void recyclesEmptyRoomsAndIgnoresStartedRooms() {
    assertEquals(WaitingArrivalTimeout.Decision.RECYCLE_EMPTY,
        WaitingArrivalTimeout.decision(CraftovGameState.WAITING, 0, 2));
    assertEquals(WaitingArrivalTimeout.Decision.INACTIVE,
        WaitingArrivalTimeout.decision(CraftovGameState.RUNNING, 1, 2));
  }
}
