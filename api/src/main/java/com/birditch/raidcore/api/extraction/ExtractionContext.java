package com.birditch.raidcore.api.extraction;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.UUID;

/// Context handed to [ExtractionCondition] checks.
public record ExtractionContext(UUID playerId, RaidGameId gameId) {

    public ExtractionContext {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(gameId, "gameId");
    }
}
