package com.birditch.escapefromcraftov.lobby.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.network.GameServerSnapshot;
import com.birditch.escapefromcraftov.api.network.InstanceSnapshot;
import com.birditch.escapefromcraftov.api.network.ServerId;
import com.birditch.escapefromcraftov.lobby.LobbyConfig;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LobbyMapCatalogTest {

  @Test
  void mergesConfiguredMapsWithMapsAdvertisedByLiveRooms() {
    CraftovMapId configured = CraftovMapId.of("debug_zone");
    CraftovMapId imported = CraftovMapId.of("factory");
    LobbyConfig config = new LobbyConfig(
        1, 2, 30, 30, true, true, 20, 5, configured, Set.of(configured));
    InstanceSnapshot room = new InstanceSnapshot(CraftovGameId.of("abc123"), imported,
        CraftovGameState.WAITING, 0, 12, true);
    GameServerSnapshot server = new GameServerSnapshot(ServerId.of("game-01"), "craftov-01",
        1L, 4, List.of(room));

    assertEquals(Set.of(configured, imported),
        LobbyMapCatalog.availableMaps(config, List.of(server)));
  }

  @Test
  void deduplicatesConfiguredAndAdvertisedMaps() {
    CraftovMapId map = CraftovMapId.of("factory");
    LobbyConfig config = new LobbyConfig(
        1, 2, 30, 30, true, true, 20, 5, map, Set.of(map));
    InstanceSnapshot room = new InstanceSnapshot(CraftovGameId.of("abc123"), map,
        CraftovGameState.RUNNING, 8, 12, true);
    GameServerSnapshot server = new GameServerSnapshot(ServerId.of("game-01"), "craftov-01",
        1L, 4, List.of(room));

    assertEquals(Set.of(map), LobbyMapCatalog.availableMaps(config, List.of(server)));
  }
}
