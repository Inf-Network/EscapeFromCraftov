package com.birditch.raidcore.lobby.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.network.GameServerSnapshot;
import com.birditch.raidcore.api.network.InstanceSnapshot;
import com.birditch.raidcore.api.network.ServerId;
import com.birditch.raidcore.lobby.LobbyConfig;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LobbyMapCatalogTest {

  @Test
  void mergesConfiguredMapsWithMapsAdvertisedByLiveRooms() {
    RaidMapId configured = RaidMapId.of("debug_zone");
    RaidMapId imported = RaidMapId.of("factory");
    LobbyConfig config = new LobbyConfig(
        1, 30, 30, true, true, 20, 5, configured, Set.of(configured));
    InstanceSnapshot room = new InstanceSnapshot(RaidGameId.of("abc123"), imported,
        RaidGameState.WAITING, 0, 12, true);
    GameServerSnapshot server = new GameServerSnapshot(ServerId.of("game-01"), "raid-01",
        1L, 4, List.of(room));

    assertEquals(Set.of(configured, imported),
        LobbyMapCatalog.availableMaps(config, List.of(server)));
  }

  @Test
  void deduplicatesConfiguredAndAdvertisedMaps() {
    RaidMapId map = RaidMapId.of("factory");
    LobbyConfig config = new LobbyConfig(
        1, 30, 30, true, true, 20, 5, map, Set.of(map));
    InstanceSnapshot room = new InstanceSnapshot(RaidGameId.of("abc123"), map,
        RaidGameState.RUNNING, 8, 12, true);
    GameServerSnapshot server = new GameServerSnapshot(ServerId.of("game-01"), "raid-01",
        1L, 4, List.of(room));

    assertEquals(Set.of(map), LobbyMapCatalog.availableMaps(config, List.of(server)));
  }
}
