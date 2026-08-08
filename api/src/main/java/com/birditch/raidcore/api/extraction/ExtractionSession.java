package com.birditch.raidcore.api.extraction;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.common.check.Preconditions;
import java.time.Instant;
import java.util.UUID;

/// An in-progress extraction attempt. Main-thread confined; starts at
/// [ExtractionState#EXTRACTING] and settles exactly once.
public final class ExtractionSession {

    private final UUID playerId;
    private final RaidGameId gameId;
    private final ExtractionPointId pointId;
    private final Instant startedAt;
    private ExtractionState state = ExtractionState.EXTRACTING;

    public ExtractionSession(UUID playerId, RaidGameId gameId, ExtractionPointId pointId,
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

    public RaidGameId gameId() {
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
    public void complete() {
        requireExtracting();
        state = ExtractionState.COMPLETED;
    }

    /// @throws IllegalStateException unless currently EXTRACTING
    public void fail() {
        requireExtracting();
        state = ExtractionState.FAILED;
    }

    private void requireExtracting() {
        if (state != ExtractionState.EXTRACTING) {
            throw new IllegalStateException("Extraction session already settled: " + state);
        }
    }
}
