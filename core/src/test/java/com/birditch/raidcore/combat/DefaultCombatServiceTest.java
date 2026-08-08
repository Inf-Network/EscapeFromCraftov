package com.birditch.raidcore.combat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.birditch.raidcore.api.combat.PlayerCombatState;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("DefaultCombatService")
class DefaultCombatServiceTest {

  private final DefaultCombatService service = new DefaultCombatService();
  private final UUID playerId = UUID.randomUUID();

  @Nested
  @DisplayName("stateOf")
  class StateOf {

    @Test
    @DisplayName("returns ALIVE for an untracked player")
    void untrackedPlayerIsAlive() {
      assertEquals(PlayerCombatState.ALIVE, service.stateOf(playerId));
    }

    @Test
    @DisplayName("rejects null playerId")
    void rejectsNullPlayerId() {
      assertThrows(NullPointerException.class, () -> service.stateOf(null));
    }
  }

  @Nested
  @DisplayName("setState")
  class SetState {

    @ParameterizedTest
    @EnumSource(PlayerCombatState.class)
    @DisplayName("stores every combat state")
    void storesEveryState(PlayerCombatState state) {
      service.setState(playerId, state);

      assertEquals(state, service.stateOf(playerId));
    }

    @Test
    @DisplayName("tracks players independently")
    void tracksPlayersIndependently() {
      UUID otherId = UUID.randomUUID();

      service.setState(playerId, PlayerCombatState.DEAD);

      assertEquals(PlayerCombatState.DEAD, service.stateOf(playerId));
      assertEquals(PlayerCombatState.ALIVE, service.stateOf(otherId));
    }

    @Test
    @DisplayName("rejects null arguments")
    void rejectsNullArguments() {
      assertThrows(NullPointerException.class,
          () -> service.setState(null, PlayerCombatState.ALIVE));
      assertThrows(NullPointerException.class, () -> service.setState(playerId, null));
    }
  }

  @Nested
  @DisplayName("reset")
  class Reset {

    @Test
    @DisplayName("returns a tracked player to the ALIVE default")
    void returnsTrackedPlayerToDefault() {
      service.setState(playerId, PlayerCombatState.DOWNED);

      service.reset(playerId);

      assertEquals(PlayerCombatState.ALIVE, service.stateOf(playerId));
    }

    @Test
    @DisplayName("is a no-op for an untracked player")
    void noOpForUntrackedPlayer() {
      assertDoesNotThrow(() -> service.reset(playerId));
      assertEquals(PlayerCombatState.ALIVE, service.stateOf(playerId));
    }
  }
}
