package com.birditch.escapefromcraftov.api.event.extraction;

import com.birditch.escapefromcraftov.api.event.CraftovEvent;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/// Published when the countdown was earned and the player must be frozen while
/// durable extraction conditions are authorized.
public record PlayerExtractionLockedEvent(
    CraftovGameId gameId, UUID playerId, ExtractionPointId pointId) implements CraftovEvent {

  public PlayerExtractionLockedEvent {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(pointId, "pointId");
  }
}
