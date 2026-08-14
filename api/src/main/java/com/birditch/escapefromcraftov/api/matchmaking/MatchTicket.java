package com.birditch.escapefromcraftov.api.matchmaking;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.Instant;
import java.util.UUID;

/// A player's place in the matchmaking queue.
///
/// [#teamKey] identifies players that deliberately queued as one party. A solo
/// ticket uses its own player UUID, so unrelated tickets never become allies
/// merely because they reached the same game instance consecutively.
public record MatchTicket(UUID playerId, CraftovMapId mapId, Instant queuedAt, UUID teamKey) {

    /// Creates the default solo ticket. Party matchmaking may use the
    /// four-argument constructor with one shared key for all party members.
    public MatchTicket(UUID playerId, CraftovMapId mapId, Instant queuedAt) {
        this(playerId, mapId, queuedAt, playerId);
    }

    public MatchTicket {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(mapId, "mapId");
        Preconditions.checkNotNull(queuedAt, "queuedAt");
        Preconditions.checkNotNull(teamKey, "teamKey");
    }
}
