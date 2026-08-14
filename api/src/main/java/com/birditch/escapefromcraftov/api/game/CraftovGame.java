package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// Read view of a craftov game. Main-thread only; returned collections are
/// unmodifiable.
public interface CraftovGame {

    CraftovGameId id();

    CraftovMapId mapId();

    default RaidMode mode() {
        return RaidMode.NORMAL;
    }

    default RaidDifficulty difficulty() {
        return RaidDifficulty.NORMAL;
    }

    /// Canonical map-authored action tier captured when this room was created.
    /// A null value identifies a legacy room that is still addressed solely by
    /// its mode+difficulty pair.
    default @Nullable RaidTierId tierId() {
        return null;
    }

    /// Complete immutable action profile selected for this room. Legacy rooms
    /// deliberately expose no profile so they retain their historical ruleset.
    default Optional<RaidActionProfile> actionProfile() {
        return Optional.empty();
    }

    /// Authored map revision captured at game creation. It never changes mid-craftov.
    default long mapRevision() {
        return 0;
    }

    default List<CraftovSpawnLocation> spawnLocations() {
        return List.of();
    }

    default List<CraftovExtractionArea> extractionAreas() {
        return List.of();
    }

    default List<CraftovLootPoint> lootPoints() {
        return List.of();
    }

    default List<CraftovLootZone> lootZones() {
        return List.of();
    }

    default List<CraftovAiZone> aiZones() {
        return List.of();
    }

    default List<CraftovPoi> pois() {
        return List.of();
    }

    CraftovGameState state();

    Collection<CraftovPlayer> players();

    Collection<CraftovTeam> teams();

    List<ExtractionPoint> extractionPoints();

    Instant createdAt();

    Optional<Instant> startedAt();

    Optional<CraftovResult> result();

    int playerCount();
}
