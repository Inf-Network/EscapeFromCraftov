package com.birditch.escapefromcraftov.lobby.command;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.network.GameServerSnapshot;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.lobby.LobbyConfig;
import com.birditch.escapefromcraftov.lobby.LobbyActionCatalog;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Merges the operator allow-list with maps advertised by live game rooms.
/// This lets a newly imported map become discoverable as soon as an admin
/// creates its first room, without making the lobby read game-server files.
final class LobbyMapCatalog {

  private LobbyMapCatalog() {}

  static Set<CraftovMapId> availableMaps(LobbyConfig config,
      List<GameServerSnapshot> servers) {
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(servers, "servers");
    LinkedHashSet<CraftovMapId> maps = new LinkedHashSet<>(config.enabledMaps());
    servers.stream()
        .flatMap(server -> server.instances().stream())
        .map(instance -> instance.mapId())
        .sorted(Comparator.comparing(CraftovMapId::value))
        .forEach(maps::add);
    LobbyActionCatalog.fromServers(servers).advertisedMaps().stream()
        .sorted(Comparator.comparing(CraftovMapId::value))
        .forEach(maps::add);
    return Set.copyOf(maps);
  }
}
