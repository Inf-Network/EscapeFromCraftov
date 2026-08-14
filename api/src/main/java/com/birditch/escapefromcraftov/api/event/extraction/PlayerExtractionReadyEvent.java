package com.birditch.escapefromcraftov.api.event.extraction;

import com.birditch.escapefromcraftov.api.event.CraftovEvent;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/// Published when the in-world extraction timer completed and durable
/// inventory settlement must begin. This is not yet a successful extraction.
public record PlayerExtractionReadyEvent(
    CraftovGameId gameId, UUID playerId, ExtractionPointId pointId) implements CraftovEvent {

  public PlayerExtractionReadyEvent {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(pointId, "pointId");
  }
}
