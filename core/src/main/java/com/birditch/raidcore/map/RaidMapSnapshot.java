package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.List;
import java.util.Map;

/// Immutable authored revision captured when a raid instance is created.
public record RaidMapSnapshot(
    String mapId,
    String worldName,
    long revision,
    CuboidBounds bounds,
    Map<String, String> metadata,
    List<SpawnDefinition> spawns,
    List<LootPointDefinition> lootPoints,
    List<LootZoneDefinition> lootZones,
    List<AiZoneDefinition> aiZones,
    List<ExtractionDefinition> extractions,
    List<PoiDefinition> pois) {

  public RaidMapSnapshot {
    MapIds.requireValid(mapId, "mapId");
    Preconditions.checkNotNull(worldName, "worldName");
    Preconditions.checkArgument(revision >= 0, "revision must not be negative");
    Preconditions.checkNotNull(bounds, "bounds");
    metadata = Map.copyOf(Preconditions.checkNotNull(metadata, "metadata"));
    spawns = List.copyOf(Preconditions.checkNotNull(spawns, "spawns"));
    lootPoints = List.copyOf(Preconditions.checkNotNull(lootPoints, "lootPoints"));
    lootZones = List.copyOf(Preconditions.checkNotNull(lootZones, "lootZones"));
    aiZones = List.copyOf(Preconditions.checkNotNull(aiZones, "aiZones"));
    extractions = List.copyOf(Preconditions.checkNotNull(extractions, "extractions"));
    pois = List.copyOf(Preconditions.checkNotNull(pois, "pois"));
  }

  public static RaidMapSnapshot capture(ManualRaidMap map) {
    Preconditions.checkNotNull(map, "map");
    Preconditions.checkArgument(map.enabled(), "map must be enabled before capture");
    Preconditions.checkArgument(map.bounds() != null, "map bounds are required before capture");
    return new RaidMapSnapshot(map.mapId(), map.worldName(), map.revision(), map.bounds(),
        map.metadata(), map.spawns(), map.lootPoints(), map.lootZones(), map.aiZones(),
        map.extractions(), map.pois());
  }
}
