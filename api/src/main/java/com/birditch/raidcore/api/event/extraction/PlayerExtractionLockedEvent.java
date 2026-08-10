package com.birditch.raidcore.api.event.extraction;

import com.birditch.raidcore.api.event.RaidEvent;
import com.birditch.raidcore.api.extraction.ExtractionPointId;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.UUID;

/// Published when the countdown was earned and the player must be frozen while
/// durable extraction conditions are authorized.
public record PlayerExtractionLockedEvent(
    RaidGameId gameId, UUID playerId, ExtractionPointId pointId) implements RaidEvent {

  public PlayerExtractionLockedEvent {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(pointId, "pointId");
  }
}
