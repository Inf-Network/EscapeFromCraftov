package com.birditch.raidcore.api.game;

import com.birditch.raidcore.common.check.Preconditions;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/// Final outcome of a raid game; player sets are stored as immutable copies.
public record RaidResult(RaidGameId gameId, Instant endedAt, Set<UUID> extractedPlayers,
                         Set<UUID> remainingPlayers) {

    public RaidResult {
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(endedAt, "endedAt");
        Preconditions.checkNotNull(extractedPlayers, "extractedPlayers");
        Preconditions.checkNotNull(remainingPlayers, "remainingPlayers");
        extractedPlayers = Set.copyOf(extractedPlayers);
        remainingPlayers = Set.copyOf(remainingPlayers);
    }
}
