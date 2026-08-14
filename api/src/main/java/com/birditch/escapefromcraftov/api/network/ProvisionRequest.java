package com.birditch.escapefromcraftov.api.network;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Structured Redis pub/sub request asking one game server to prepare capacity
/// for a specific map.
public record ProvisionRequest(ServerId serverId, CraftovMapId mapId) {

  public ProvisionRequest {
    Preconditions.checkNotNull(serverId, "serverId");
    Preconditions.checkNotNull(mapId, "mapId");
  }
}
