package com.birditch.escapefromcraftov.lobby;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/// Main-thread gate for Lobby actions that require all PostgreSQL-backed
/// player data to be loaded successfully.
public final class LobbyDataGate {

  private final Set<UUID> readyPlayers = new HashSet<>();

  public void markReady(UUID playerId) {
    readyPlayers.add(Preconditions.checkNotNull(playerId, "playerId"));
  }

  public boolean isReady(UUID playerId) {
    return readyPlayers.contains(Preconditions.checkNotNull(playerId, "playerId"));
  }

  public void clear(UUID playerId) {
    readyPlayers.remove(Preconditions.checkNotNull(playerId, "playerId"));
  }
}
