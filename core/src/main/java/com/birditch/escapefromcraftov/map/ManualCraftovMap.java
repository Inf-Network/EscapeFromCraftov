package com.birditch.escapefromcraftov.map;

import com.birditch.escapefromcraftov.api.game.LegacyRaidTierResolver;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// Immutable, human-authored map revision. Lists retain authoring order.
public record ManualCraftovMap(
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
    List<PoiDefinition> pois,
    List<RaidActionProfile> actionTiers) {

  public static final int SCHEMA_VERSION = 1;

  public ManualCraftovMap {
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
    actionTiers = List.copyOf(Preconditions.checkNotNull(actionTiers, "actionTiers"));
    Preconditions.checkArgument(!actionTiers.isEmpty(), "actionTiers must not be empty");
    HashSet<RaidTierId> tierIds = new HashSet<>();
    HashSet<RaidActionProfile.LegacyAlias> aliases = new HashSet<>();
    for (RaidActionProfile profile : actionTiers) {
      Preconditions.checkArgument(tierIds.add(profile.id()),
          "actionTiers must not contain duplicate id: " + profile.id().value());
      for (RaidActionProfile.LegacyAlias alias : profile.legacyAliases()) {
        Preconditions.checkArgument(aliases.add(alias),
            "actionTiers must not contain duplicate legacy alias: " + alias);
      }
    }
  }

  /// Source-compatible constructor for schema v1 maps that predate action tiers.
  public ManualCraftovMap(int schemaVersion, String mapId, String displayName, String worldName,
      @Nullable CuboidBounds bounds, long revision, boolean enabled, Map<String, String> metadata,
      List<SpawnDefinition> spawns, List<LootPointDefinition> lootPoints,
      List<LootZoneDefinition> lootZones, List<AiZoneDefinition> aiZones,
      List<ExtractionDefinition> extractions, List<PoiDefinition> pois) {
    this(schemaVersion, mapId, displayName, worldName, bounds, revision, enabled, metadata,
        spawns, lootPoints, lootZones, aiZones, extractions, pois,
        LegacyRaidTierResolver.defaultProfiles());
  }

  public static ManualCraftovMap create(String mapId, String worldName) {
    return new ManualCraftovMap(SCHEMA_VERSION, mapId, mapId, worldName, null, 0, false,
        Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  /**
   * Creates a new, disabled revision from an imported map package while
   * preserving all authored objects. The target id and source world are the
   * only fields changed by the import operation.
   */
  public ManualCraftovMap copyAs(String targetMapId, String targetWorldName) {
    return new ManualCraftovMap(schemaVersion, targetMapId, displayName, targetWorldName, bounds,
        0, false, metadata, spawns, lootPoints, lootZones, aiZones, extractions, pois,
        actionTiers);
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

  public Optional<RaidActionProfile> actionTier(RaidTierId id) {
    Preconditions.checkNotNull(id, "id");
    return actionTiers.stream().filter(profile -> profile.id().equals(id)).findFirst();
  }

  public ManualCraftovMap withBounds(CuboidBounds newBounds) {
    return copy(newBounds, revision, enabled, spawns, lootPoints, lootZones, aiZones,
        extractions, pois);
  }

  public ManualCraftovMap withRevision(long newRevision, boolean newEnabled) {
    return copy(bounds, newRevision, newEnabled, spawns, lootPoints, lootZones, aiZones,
        extractions, pois);
  }

  public ManualCraftovMap withEnabled(boolean newEnabled) {
    return copy(bounds, revision, newEnabled, spawns, lootPoints, lootZones, aiZones,
        extractions, pois);
  }

  ManualCraftovMap withObjects(List<SpawnDefinition> newSpawns,
      List<LootPointDefinition> newLootPoints, List<LootZoneDefinition> newLootZones,
      List<AiZoneDefinition> newAiZones, List<ExtractionDefinition> newExtractions,
      List<PoiDefinition> newPois) {
    return copy(bounds, revision, enabled, newSpawns, newLootPoints, newLootZones, newAiZones,
        newExtractions, newPois);
  }

  private ManualCraftovMap copy(@Nullable CuboidBounds newBounds, long newRevision,
      boolean newEnabled, List<SpawnDefinition> newSpawns,
      List<LootPointDefinition> newLootPoints, List<LootZoneDefinition> newLootZones,
      List<AiZoneDefinition> newAiZones, List<ExtractionDefinition> newExtractions,
      List<PoiDefinition> newPois) {
    return new ManualCraftovMap(schemaVersion, mapId, displayName, worldName, newBounds,
        newRevision, newEnabled, metadata, newSpawns, newLootPoints, newLootZones, newAiZones,
        newExtractions, newPois, actionTiers);
  }
}
