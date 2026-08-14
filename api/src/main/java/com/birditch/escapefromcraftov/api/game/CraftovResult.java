package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/// Final outcome of a craftov game; player sets are stored as immutable copies.
public record CraftovResult(CraftovGameId gameId, Instant endedAt, Set<UUID> extractedPlayers,
                         Set<UUID> remainingPlayers) {

    public CraftovResult {
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(endedAt, "endedAt");
        Preconditions.checkNotNull(extractedPlayers, "extractedPlayers");
        Preconditions.checkNotNull(remainingPlayers, "remainingPlayers");
        extractedPlayers = Set.copyOf(extractedPlayers);
        remainingPlayers = Set.copyOf(remainingPlayers);
    }
}
