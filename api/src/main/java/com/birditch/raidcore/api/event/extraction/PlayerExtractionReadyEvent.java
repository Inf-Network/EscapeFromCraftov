package com.birditch.raidcore.api.event.extraction;

import com.birditch.raidcore.api.event.RaidEvent;
import com.birditch.raidcore.api.extraction.ExtractionPointId;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.UUID;

/// Published when the in-world extraction timer completed and durable
/// inventory settlement must begin. This is not yet a successful extraction.
public record PlayerExtractionReadyEvent(
    RaidGameId gameId, UUID playerId, ExtractionPointId pointId) implements RaidEvent {

  public PlayerExtractionReadyEvent {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(pointId, "pointId");
  }
}
