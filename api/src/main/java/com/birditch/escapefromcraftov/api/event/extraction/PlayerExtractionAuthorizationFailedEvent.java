package com.birditch.escapefromcraftov.api.event.extraction;

import com.birditch.escapefromcraftov.api.event.CraftovEvent;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/// Published when a completed countdown fails a durable condition, such as an
/// authoritative balance check for a paid exit.
public record PlayerExtractionAuthorizationFailedEvent(
    CraftovGameId gameId, UUID playerId, ExtractionPointId pointId,
    String reason) implements CraftovEvent {

  public PlayerExtractionAuthorizationFailedEvent {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(pointId, "pointId");
    Preconditions.checkNotNull(reason, "reason");
  }
}
