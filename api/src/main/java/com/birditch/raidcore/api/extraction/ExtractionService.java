package com.birditch.raidcore.api.extraction;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.common.result.Result;
import java.util.Optional;
import java.util.UUID;

/// Runs the extraction workflow. Main-thread only.
public interface ExtractionService {

    Result<ExtractionSession> begin(UUID playerId, RaidGameId gameId, ExtractionPointId pointId);

    /// Cancels the player's active extraction; returns whether one was cancelled.
    boolean cancel(UUID playerId);

    Optional<ExtractionSession> activeSession(UUID playerId);

    int activeCount();
}
