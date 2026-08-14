package com.birditch.escapefromcraftov.api.extraction;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.Instant;
import java.util.UUID;

/// A main-thread-confined extraction attempt. The in-world timer moves it from
/// [ExtractionState#EXTRACTING] to [ExtractionState#SETTLING]; only a durable
/// inventory transaction may then complete it.
public final class ExtractionSession {

    private final UUID playerId;
    private final CraftovGameId gameId;
    private final ExtractionPointId pointId;
    private final Instant startedAt;
    private ExtractionState state = ExtractionState.EXTRACTING;

    public ExtractionSession(UUID playerId, CraftovGameId gameId, ExtractionPointId pointId,
                             Instant startedAt) {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(pointId, "pointId");
        Preconditions.checkNotNull(startedAt, "startedAt");
        this.playerId = playerId;
        this.gameId = gameId;
        this.pointId = pointId;
        this.startedAt = startedAt;
    }

    public UUID playerId() {
        return playerId;
    }

    public CraftovGameId gameId() {
        return gameId;
    }

    public ExtractionPointId pointId() {
        return pointId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public ExtractionState state() {
        return state;
    }

    /// @throws IllegalStateException unless currently EXTRACTING
    public void beginSettlement() {
        requireExtracting();
        state = ExtractionState.SETTLING;
    }

    /// @throws IllegalStateException unless currently SETTLING
    public void complete() {
        if (state != ExtractionState.SETTLING) {
            throw new IllegalStateException("Extraction session is not settling: " + state);
        }
        state = ExtractionState.COMPLETED;
    }

    /// Fails either an in-world timer or a pending durable settlement.
    public void fail() {
        if (state != ExtractionState.EXTRACTING && state != ExtractionState.SETTLING) {
            throw new IllegalStateException("Extraction session already settled: " + state);
        }
        state = ExtractionState.FAILED;
    }

    private void requireExtracting() {
        if (state != ExtractionState.EXTRACTING) {
            throw new IllegalStateException("Extraction session already settled: " + state);
        }
    }
}
