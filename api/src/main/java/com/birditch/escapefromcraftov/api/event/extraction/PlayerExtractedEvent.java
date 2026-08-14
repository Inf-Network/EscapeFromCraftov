package com.birditch.escapefromcraftov.api.event.extraction;

import com.birditch.escapefromcraftov.api.event.CraftovEvent;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/// Published after a player has successfully extracted from a craftov.
public record PlayerExtractedEvent(CraftovGameId gameId, UUID playerId, ExtractionPointId pointId)
        implements CraftovEvent {

    public PlayerExtractedEvent {
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(pointId, "pointId");
    }
}
