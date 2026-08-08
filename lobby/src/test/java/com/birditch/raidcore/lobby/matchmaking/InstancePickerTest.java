package com.birditch.raidcore.lobby.matchmaking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.network.GameServerSnapshot;
import com.birditch.raidcore.api.network.InstanceSnapshot;
import com.birditch.raidcore.api.network.ServerId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InstancePicker")
class InstancePickerTest {

  private static final ServerId SERVER_A = ServerId.of("game-01");
  private static final ServerId SERVER_B = ServerId.of("game-02");
  private static final RaidMapId MAP = RaidMapId.of("debug_zone");

  private static InstanceSnapshot instance(String gameId, RaidGameState state, int players,
      int max, boolean worldReady) {
    return new InstanceSnapshot(RaidGameId.of(gameId), MAP, state, players, max, worldReady);
  }

  private static GameServerSnapshot server(ServerId id, InstanceSnapshot... instances) {
    return new GameServerSnapshot(id, id.value(), 1L, 4, List.of(instances));
  }

  @Nested
  @DisplayName("eligibility")
  class Eligibility {

    @Test
    @DisplayName("only WAITING instances with a ready world and free capacity are picked")
    void filtersState() {
      GameServerSnapshot snapshot = server(SERVER_A,
          instance("aaaaaa", RaidGameState.RUNNING, 0, 12, true),
          instance("bbbbbb", RaidGameState.WAITING, 0, 12, false),
          instance("cccccc", RaidGameState.WAITING, 12, 12, true),
          instance("dddddd", RaidGameState.WAITING, 3, 12, true));
      Optional<InstancePicker.Candidate> pick = InstancePicker.pick(List.of(snapshot), Map.of());
      assertTrue(pick.isPresent());
      assertEquals("dddddd", pick.orElseThrow().instance().gameId().value());
    }

    @Test
    @DisplayName("reservations count against capacity")
    void reservationsBlockFullInstances() {
      InstanceSnapshot nearlyFull = instance("aaaaaa", RaidGameState.WAITING, 11, 12, true);
      String key = InstancePicker.reservationKey(SERVER_A, nearlyFull.gameId());
      Optional<InstancePicker.Candidate> pick =
          InstancePicker.pick(List.of(server(SERVER_A, nearlyFull)), Map.of(key, 1));
      assertTrue(pick.isEmpty());
    }

    @Test
    @DisplayName("no servers means no candidate")
    void emptyDirectory() {
      assertTrue(InstancePicker.pick(List.of(), Map.of()).isEmpty());
    }
  }

  @Nested
  @DisplayName("ordering")
  class Ordering {

    @Test
    @DisplayName("the fullest eligible instance wins, including reservations")
    void fillFirst() {
      GameServerSnapshot snapshot = server(SERVER_A,
          instance("empty1", RaidGameState.WAITING, 0, 12, true),
          instance("fuller", RaidGameState.WAITING, 5, 12, true));
      String emptyKey = InstancePicker.reservationKey(SERVER_A, RaidGameId.of("empty1"));
      Optional<InstancePicker.Candidate> pick =
          InstancePicker.pick(List.of(snapshot), Map.of(emptyKey, 7));
      assertEquals("empty1", pick.orElseThrow().instance().gameId().value());
    }

    @Test
    @DisplayName("equal load tiebreaks on the lowest game id across servers")
    void tiebreakOnGameId() {
      GameServerSnapshot first = server(SERVER_A,
          instance("bbbbbb", RaidGameState.WAITING, 3, 12, true));
      GameServerSnapshot second = server(SERVER_B,
          instance("aaaaaa", RaidGameState.WAITING, 3, 12, true));
      Optional<InstancePicker.Candidate> pick =
          InstancePicker.pick(List.of(first, second), Map.of());
      assertEquals("aaaaaa", pick.orElseThrow().instance().gameId().value());
      assertEquals(SERVER_B, pick.orElseThrow().server().serverId());
    }
  }

  @Test
  @DisplayName("reservationKey is <serverId>:<gameId>")
  void reservationKeyFormat() {
    assertEquals("game-01:abc123",
        InstancePicker.reservationKey(SERVER_A, RaidGameId.of("abc123")));
  }
}
