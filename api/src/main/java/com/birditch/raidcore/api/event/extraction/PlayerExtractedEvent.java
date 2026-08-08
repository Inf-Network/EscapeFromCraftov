package com.birditch.raidcore.api.event.extraction;

import com.birditch.raidcore.api.event.RaidEvent;
import com.birditch.raidcore.api.extraction.ExtractionPointId;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.UUID;

/// Published after a player has successfully extracted from a raid.
public record PlayerExtractedEvent(RaidGameId gameId, UUID playerId, ExtractionPointId pointId)
        implements RaidEvent {

    public PlayerExtractedEvent {
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(pointId, "pointId");
    }
}
