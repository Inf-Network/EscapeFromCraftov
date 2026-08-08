package com.birditch.raidcore.api.matchmaking;

import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.common.check.Preconditions;
import java.time.Instant;
import java.util.UUID;

/// A player's place in the matchmaking queue.
public record MatchTicket(UUID playerId, RaidMapId mapId, Instant queuedAt) {

    public MatchTicket {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(mapId, "mapId");
        Preconditions.checkNotNull(queuedAt, "queuedAt");
    }
}
