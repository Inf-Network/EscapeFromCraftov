package com.birditch.raidcore.api.network;

import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.common.check.Preconditions;

/// Structured Redis pub/sub request asking one game server to prepare capacity
/// for a specific map.
public record ProvisionRequest(ServerId serverId, RaidMapId mapId) {

  public ProvisionRequest {
    Preconditions.checkNotNull(serverId, "serverId");
    Preconditions.checkNotNull(mapId, "mapId");
  }
}
