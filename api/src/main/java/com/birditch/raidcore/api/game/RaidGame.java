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

    /// Authored map revision captured at game creation. It never changes mid-raid.
    default long mapRevision() {
        return 0;
    }

    default List<RaidSpawnLocation> spawnLocations() {
        return List.of();
    }

    default List<RaidExtractionArea> extractionAreas() {
        return List.of();
    }

    default List<RaidLootPoint> lootPoints() {
        return List.of();
    }

    default List<RaidAiZone> aiZones() {
        return List.of();
    }

    default List<RaidPoi> pois() {
        return List.of();
    }

    RaidGameState state();

    Collection<RaidPlayer> players();

    Collection<RaidTeam> teams();

    List<ExtractionPoint> extractionPoints();

    Instant createdAt();

    Optional<Instant> startedAt();

    Optional<RaidResult> result();

    int playerCount();
}
