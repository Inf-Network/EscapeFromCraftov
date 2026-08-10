package com.birditch.raidcore.automap;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.List;
import java.util.Map;

/// Complete generated map configuration persisted by the game plugin.
public record RaidMapDefinition(
    int schemaVersion,
    String generatorVersion,
    String mapId,
    String sourceWorld,
    MapBounds bounds,
    long generationSeed,
    int revision,
    long generatedAtEpochMillis,
    MapSetupState state,
    boolean enabled,
    int qualityScore,
    Map<TerrainType, Integer> terrainCounts,
    List<PointOfInterest> pointsOfInterest,
    List<SpawnRegion> spawnRegions,
    List<LootZone> lootZones,
    List<LootContainerPoint> lootPoints,
    List<AiSpawnRegion> aiRegions,
    List<ExtractionRegion> extractionRegions,
    MapValidationReport validation) {

  public RaidMapDefinition {
    Preconditions.checkNotNull(generatorVersion, "generatorVersion");
    Preconditions.checkNotNull(mapId, "mapId");
    Preconditions.checkNotNull(sourceWorld, "sourceWorld");
    Preconditions.checkNotNull(bounds, "bounds");
    Preconditions.checkNotNull(state, "state");
    Preconditions.checkNotNull(terrainCounts, "terrainCounts");
    Preconditions.checkNotNull(pointsOfInterest, "pointsOfInterest");
    Preconditions.checkNotNull(spawnRegions, "spawnRegions");
    Preconditions.checkNotNull(lootZones, "lootZones");
    Preconditions.checkNotNull(lootPoints, "lootPoints");
    Preconditions.checkNotNull(aiRegions, "aiRegions");
    Preconditions.checkNotNull(extractionRegions, "extractionRegions");
    Preconditions.checkNotNull(validation, "validation");
    terrainCounts = Map.copyOf(terrainCounts);
    pointsOfInterest = List.copyOf(pointsOfInterest);
    spawnRegions = List.copyOf(spawnRegions);
    lootZones = List.copyOf(lootZones);
    lootPoints = List.copyOf(lootPoints);
    aiRegions = List.copyOf(aiRegions);
    extractionRegions = List.copyOf(extractionRegions);
  }

  public RaidMapDefinition withState(MapSetupState newState, boolean newEnabled) {
    return new RaidMapDefinition(schemaVersion, generatorVersion, mapId, sourceWorld, bounds,
        generationSeed, revision, generatedAtEpochMillis, newState, newEnabled, qualityScore,
        terrainCounts, pointsOfInterest, spawnRegions, lootZones, lootPoints, aiRegions,
        extractionRegions, validation);
  }

  public RaidMapDefinition withRevision(int newRevision) {
    return new RaidMapDefinition(schemaVersion, generatorVersion, mapId, sourceWorld, bounds,
        generationSeed, newRevision, generatedAtEpochMillis, state, enabled, qualityScore,
        terrainCounts, pointsOfInterest, spawnRegions, lootZones, lootPoints, aiRegions,
        extractionRegions, validation);
  }
}
