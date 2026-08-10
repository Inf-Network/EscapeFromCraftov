package com.birditch.raidcore.api.event.extraction;

import com.birditch.raidcore.api.event.RaidEvent;
import com.birditch.raidcore.api.extraction.ExtractionPointId;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.UUID;

/// Published when a completed countdown fails a durable condition, such as an
/// authoritative balance check for a paid exit.
public record PlayerExtractionAuthorizationFailedEvent(
    RaidGameId gameId, UUID playerId, ExtractionPointId pointId,
    String reason) implements RaidEvent {

  public PlayerExtractionAuthorizationFailedEvent {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(pointId, "pointId");
    Preconditions.checkNotNull(reason, "reason");
  }
}
