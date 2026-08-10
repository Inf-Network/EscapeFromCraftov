package com.birditch.raidcore.lobby.command;

import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.network.GameServerSnapshot;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.lobby.LobbyConfig;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Merges the operator allow-list with maps advertised by live game rooms.
/// This lets a newly imported map become discoverable as soon as an admin
/// creates its first room, without making the lobby read game-server files.
final class LobbyMapCatalog {

  private LobbyMapCatalog() {}

  static Set<RaidMapId> availableMaps(LobbyConfig config,
      List<GameServerSnapshot> servers) {
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(servers, "servers");
    LinkedHashSet<RaidMapId> maps = new LinkedHashSet<>(config.enabledMaps());
    servers.stream()
        .flatMap(server -> server.instances().stream())
        .map(instance -> instance.mapId())
        .sorted(Comparator.comparing(RaidMapId::value))
        .forEach(maps::add);
    return Set.copyOf(maps);
  }
}
