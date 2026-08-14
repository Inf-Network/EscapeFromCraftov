package com.birditch.escapefromcraftov.api.matchmaking;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.common.result.Result;
import java.util.Optional;
import java.util.UUID;

/// Queues players for craftov instances. Main-thread only.
public interface MatchmakingService {

    /// Fails when the player is already queued.
    Result<MatchTicket> queue(UUID playerId, CraftovMapId mapId);

    /// Returns whether the player had a ticket to remove.
    boolean dequeue(UUID playerId);

    Optional<MatchTicket> ticket(UUID playerId);

    int queueSize();
}
