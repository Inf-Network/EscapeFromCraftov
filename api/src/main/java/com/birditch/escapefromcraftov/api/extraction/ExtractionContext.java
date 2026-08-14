package com.birditch.escapefromcraftov.api.extraction;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/// Context handed to [ExtractionCondition] checks.
public record ExtractionContext(UUID playerId, CraftovGameId gameId) {

    public ExtractionContext {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(gameId, "gameId");
    }
}
