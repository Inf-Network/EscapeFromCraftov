package com.birditch.raidcore.api.game;

import com.birditch.raidcore.api.extraction.ExtractionPoint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/// Read view of a raid game. Main-thread only; returned collections are
/// unmodifiable.
public interface RaidGame {

    RaidGameId id();

    RaidMapId mapId();

    RaidGameState state();

    Collection<RaidPlayer> players();

    Collection<RaidTeam> teams();

    List<ExtractionPoint> extractionPoints();

    Instant createdAt();

    Optional<Instant> startedAt();

    Optional<RaidResult> result();

    int playerCount();
}
