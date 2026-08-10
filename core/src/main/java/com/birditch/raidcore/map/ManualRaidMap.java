package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// Immutable, human-authored map revision. Lists retain authoring order.
public record ManualRaidMap(
    int schemaVersion,
    String mapId,
    String displayName,
    String worldName,
    @Nullable CuboidBounds bounds,
    long revision,
    boolean enabled,
    Map<String, String> metadata,
    List<SpawnDefinition> spawns,
    List<LootPointDefinition> lootPoints,
    List<LootZoneDefinition> lootZones,
    List<AiZoneDefinition> aiZones,
    List<ExtractionDefinition> extractions,
    List<PoiDefinition> pois) {

  public static final int SCHEMA_VERSION = 1;

  public ManualRaidMap {
    Preconditions.checkArgument(schemaVersion > 0, "schemaVersion must be positive");
    MapIds.requireValid(mapId, "mapId");
    Preconditions.checkNotNull(displayName, "displayName");
    Preconditions.checkArgument(!displayName.isBlank(), "displayName must not be blank");
    Preconditions.checkNotNull(worldName, "worldName");
    Preconditions.checkArgument(!worldName.isBlank(), "worldName must not be blank");
    Preconditions.checkArgument(revision >= 0, "revision must not be negative");
    metadata = Map.copyOf(Preconditions.checkNotNull(metadata, "metadata"));
    spawns = List.copyOf(Preconditions.checkNotNull(spawns, "spawns"));
    lootPoints = List.copyOf(Preconditions.checkNotNull(lootPoints, "lootPoints"));
    lootZones = List.copyOf(Preconditions.checkNotNull(lootZones, "lootZones"));
    aiZones = List.copyOf(Preconditions.checkNotNull(aiZones, "aiZones"));
    extractions = List.copyOf(Preconditions.checkNotNull(extractions, "extractions"));
    pois = List.copyOf(Preconditions.checkNotNull(pois, "pois"));
  }

  public static ManualRaidMap create(String mapId, String worldName) {
    return new ManualRaidMap(SCHEMA_VERSION, mapId, mapId, worldName, null, 0, false,
        Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  /**
   * Creates a new, disabled revision from an imported map package while
   * preserving all authored objects. The target id and source world are the
   * only fields changed by the import operation.
   */
  public ManualRaidMap copyAs(String targetMapId, String targetWorldName) {
    return new ManualRaidMap(schemaVersion, targetMapId, displayName, targetWorldName, bounds,
        0, false, metadata, spawns, lootPoints, lootZones, aiZones, extractions, pois);
  }

  public List<MapObject> objects() {
    List<MapObject> result = new ArrayList<>(spawns.size() + lootPoints.size()
        + lootZones.size() + aiZones.size() + extractions.size() + pois.size());
    result.addAll(spawns);
    result.addAll(lootPoints);
    result.addAll(lootZones);
    result.addAll(aiZones);
    result.addAll(extractions);
    result.addAll(pois);
    return List.copyOf(result);
  }

  public Optional<MapObject> findObject(String id) {
    return objects().stream().filter(object -> object.id().equals(id)).findFirst();
  }

  public Map<String, MapObject> objectsById() {
    Map<String, MapObject> result = new LinkedHashMap<>();
    for (MapObject object : objects()) result.putIfAbsent(object.id(), object);
    return Map.copyOf(result);
  }

  public ManualRaidMap withBounds(CuboidBounds newBounds) {
    return copy(newBounds, revision, enabled, spawns, lootPoints, lootZones, aiZones,
        extractions, pois);
  }

  public ManualRaidMap withRevision(long newRevision, boolean newEnabled) {
    return copy(bounds, newRevision, newEnabled, spawns, lootPoints, lootZones, aiZones,
        extractions, pois);
  }

  public ManualRaidMap withEnabled(boolean newEnabled) {
    return copy(bounds, revision, newEnabled, spawns, lootPoints, lootZones, aiZones,
        extractions, pois);
  }

  ManualRaidMap withObjects(List<SpawnDefinition> newSpawns,
      List<LootPointDefinition> newLootPoints, List<LootZoneDefinition> newLootZones,
      List<AiZoneDefinition> newAiZones, List<ExtractionDefinition> newExtractions,
      List<PoiDefinition> newPois) {
    return copy(bounds, revision, enabled, newSpawns, newLootPoints, newLootZones, newAiZones,
        newExtractions, newPois);
  }

  private ManualRaidMap copy(@Nullable CuboidBounds newBounds, long newRevision,
      boolean newEnabled, List<SpawnDefinition> newSpawns,
      List<LootPointDefinition> newLootPoints, List<LootZoneDefinition> newLootZones,
      List<AiZoneDefinition> newAiZones, List<ExtractionDefinition> newExtractions,
      List<PoiDefinition> newPois) {
    return new ManualRaidMap(schemaVersion, mapId, displayName, worldName, newBounds,
        newRevision, newEnabled, metadata, newSpawns, newLootPoints, newLootZones, newAiZones,
        newExtractions, newPois);
  }
}
