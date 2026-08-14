package com.birditch.escapefromcraftov.api.extraction;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.result.Result;
import java.util.Optional;
import java.util.UUID;

/// Runs the extraction workflow. Main-thread only.
public interface ExtractionService {

    Result<ExtractionSession> begin(UUID playerId, CraftovGameId gameId, ExtractionPointId pointId);

    /// Cancels the player's active extraction; returns whether one was cancelled.
    /// A session whose timer already completed is SETTLING and cannot be cancelled.
    boolean cancel(UUID playerId);

    /// Marks a matching SETTLING session complete after durable inventory settlement.
    boolean completeSettlement(UUID playerId, CraftovGameId gameId, ExtractionPointId pointId);

    /// Fails and removes a matching SETTLING session after a terminal settlement conflict.
    boolean failSettlement(UUID playerId, CraftovGameId gameId, ExtractionPointId pointId);

    Optional<ExtractionSession> activeSession(UUID playerId);

    int activeCount();
}
