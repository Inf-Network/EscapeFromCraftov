package com.birditch.raidcore.api.matchmaking;

import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.common.result.Result;
import java.util.Optional;
import java.util.UUID;

/// Queues players for raid instances. Main-thread only.
public interface MatchmakingService {

    /// Fails when the player is already queued.
    Result<MatchTicket> queue(UUID playerId, RaidMapId mapId);

    /// Returns whether the player had a ticket to remove.
    boolean dequeue(UUID playerId);

    Optional<MatchTicket> ticket(UUID playerId);

    int queueSize();
}
