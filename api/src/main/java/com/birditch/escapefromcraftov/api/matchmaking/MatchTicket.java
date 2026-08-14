package com.birditch.escapefromcraftov.api.matchmaking;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.Instant;
import java.util.UUID;

/// A player's place in the matchmaking queue.
public record MatchTicket(UUID playerId, CraftovMapId mapId, Instant queuedAt) {

    public MatchTicket {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(mapId, "mapId");
        Preconditions.checkNotNull(queuedAt, "queuedAt");
    }
}
