package com.birditch.raidcore.api.extraction;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.common.result.Result;
import java.util.Optional;
import java.util.UUID;

/// Runs the extraction workflow. Main-thread only.
public interface ExtractionService {

    Result<ExtractionSession> begin(UUID playerId, RaidGameId gameId, ExtractionPointId pointId);

    /// Cancels the player's active extraction; returns whether one was cancelled.
    /// A session whose timer already completed is SETTLING and cannot be cancelled.
    boolean cancel(UUID playerId);

    /// Marks a matching SETTLING session complete after durable inventory settlement.
    boolean completeSettlement(UUID playerId, RaidGameId gameId, ExtractionPointId pointId);

    /// Fails and removes a matching SETTLING session after a terminal settlement conflict.
    boolean failSettlement(UUID playerId, RaidGameId gameId, ExtractionPointId pointId);

    Optional<ExtractionSession> activeSession(UUID playerId);

    int activeCount();
}
