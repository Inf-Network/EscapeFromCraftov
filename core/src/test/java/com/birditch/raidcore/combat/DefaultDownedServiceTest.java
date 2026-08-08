package com.birditch.raidcore.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.combat.PlayerCombatState;
import com.birditch.raidcore.api.event.combat.PlayerDownedEvent;
import com.birditch.raidcore.api.event.combat.PlayerRevivedEvent;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.core.event.SimpleEventBus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultDownedService")
class DefaultDownedServiceTest {

  private final UUID playerId = UUID.randomUUID();
  private final UUID rescuerId = UUID.randomUUID();
  private final RaidGameId gameId = RaidGameId.of("game-1");
  private final List<PlayerDownedEvent> downedEvents = new ArrayList<>();
  private final List<PlayerRevivedEvent> revivedEvents = new ArrayList<>();

  private DefaultCombatService combat;
  private DefaultDownedService service;

  @BeforeEach
  void setUp() {
    SimpleEventBus eventBus =
        new SimpleEventBus(Logger.getLogger(DefaultDownedServiceTest.class.getName()));
    eventBus.subscribe(PlayerDownedEvent.class, downedEvents::add);
    eventBus.subscribe(PlayerRevivedEvent.class, revivedEvents::add);
    combat = new DefaultCombatService();
    service = new DefaultDownedService(combat, eventBus);
  }

  @Nested
  @DisplayName("down")
  class Down {

    @Test
    @DisplayName("marks an alive player DOWNED and publishes PlayerDownedEvent")
    void downsAlivePlayer() {
      service.down(playerId, gameId);

      assertTrue(service.isDowned(playerId));
      assertEquals(PlayerCombatState.DOWNED, combat.stateOf(playerId));
      assertEquals(List.of(new PlayerDownedEvent(playerId, gameId)), downedEvents);
    }

    @Test
    @DisplayName("keeps an already-downed player DOWNED and publishes again")
    void downsAlreadyDownedPlayer() {
      service.down(playerId, gameId);

      service.down(playerId, gameId);

      assertTrue(service.isDowned(playerId));
      assertEquals(2, downedEvents.size());
    }

    @Test
    @DisplayName("throws for a dead player and publishes nothing")
    void rejectsDeadPlayer() {
      combat.setState(playerId, PlayerCombatState.DEAD);

      assertThrows(IllegalStateException.class, () -> service.down(playerId, gameId));

      assertEquals(PlayerCombatState.DEAD, combat.stateOf(playerId));
      assertTrue(downedEvents.isEmpty());
    }
  }

  @Nested
  @DisplayName("revive")
  class Revive {

    @Test
    @DisplayName("returns a downed player to ALIVE and publishes PlayerRevivedEvent")
    void revivesDownedPlayer() {
      service.down(playerId, gameId);

      service.revive(playerId, rescuerId);

      assertFalse(service.isDowned(playerId));
      assertEquals(PlayerCombatState.ALIVE, combat.stateOf(playerId));
      assertEquals(List.of(new PlayerRevivedEvent(playerId, rescuerId)), revivedEvents);
    }

    @Test
    @DisplayName("throws for an alive player and publishes nothing")
    void rejectsAlivePlayer() {
      assertThrows(IllegalStateException.class, () -> service.revive(playerId, rescuerId));

      assertTrue(revivedEvents.isEmpty());
    }

    @Test
    @DisplayName("throws for a dead player and publishes nothing")
    void rejectsDeadPlayer() {
      combat.setState(playerId, PlayerCombatState.DEAD);

      assertThrows(IllegalStateException.class, () -> service.revive(playerId, rescuerId));

      assertEquals(PlayerCombatState.DEAD, combat.stateOf(playerId));
      assertTrue(revivedEvents.isEmpty());
    }
  }

  @Nested
  @DisplayName("isDowned")
  class IsDowned {

    @Test
    @DisplayName("is false for an untracked player")
    void falseForUntrackedPlayer() {
      assertFalse(service.isDowned(playerId));
    }

    @Test
    @DisplayName("is false after combat reset")
    void falseAfterCombatReset() {
      service.down(playerId, gameId);

      combat.reset(playerId);

      assertFalse(service.isDowned(playerId));
    }
  }
}
