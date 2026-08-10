package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// Immutable map-object mutations shared by commands, GUI actions and tests.
public final class ManualMapEditor {

  public ManualRaidMap add(ManualRaidMap map, MapObject object) {
    Preconditions.checkNotNull(map, "map");
    Preconditions.checkNotNull(object, "object");
    Preconditions.checkArgument(map.findObject(object.id()).isEmpty(),
        "Duplicate map object id: " + object.id());
    return mutate(map, object, false);
  }

  public ManualRaidMap replace(ManualRaidMap map, MapObject object) {
    Preconditions.checkNotNull(map, "map");
    Preconditions.checkNotNull(object, "object");
    Preconditions.checkArgument(map.findObject(object.id()).isPresent(),
        "Unknown map object id: " + object.id());
    return mutate(map, object, true);
  }

  public ManualRaidMap remove(ManualRaidMap map, String id) {
    Preconditions.checkNotNull(map, "map");
    Preconditions.checkNotNull(id, "id");
    Preconditions.checkArgument(map.findObject(id).isPresent(), "Unknown map object id: " + id);
    return map.withObjects(without(map.spawns(), id), without(map.lootPoints(), id),
        without(map.lootZones(), id), without(map.aiZones(), id),
        without(map.extractions(), id), without(map.pois(), id));
  }

  public ManualRaidMap move(ManualRaidMap map, String id, BlockPosition destination) {
    MapObject object = map.findObject(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown map object id: " + id));
    int dx = destination.x() - object.anchor().x();
    int dy = destination.y() - object.anchor().y();
    int dz = destination.z() - object.anchor().z();
    return replace(map, translated(object, object.id(), dx, dy, dz));
  }

  public ManualRaidMap cloneAt(ManualRaidMap map, String sourceId, String newId,
      BlockPosition destination) {
    MapObject source = map.findObject(sourceId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown map object id: " + sourceId));
    return cloneObjectAt(map, source, newId, destination);
  }

  /** Clones a session clipboard even when the original object was removed. */
  public ManualRaidMap cloneObjectAt(ManualRaidMap map, MapObject source, String newId,
      BlockPosition destination) {
    Preconditions.checkNotNull(source, "source");
    MapIds.requireValid(newId, "newId");
    int dx = destination.x() - source.anchor().x();
    int dy = destination.y() - source.anchor().y();
    int dz = destination.z() - source.anchor().z();
    return add(map, translated(source, newId, dx, dy, dz));
  }

  public ManualRaidMap setEnabledIn(ManualRaidMap map, CuboidBounds selection,
      @Nullable MapObjectType type, boolean enabled) {
    return batchReplace(map, selection, type, object -> withEnabled(object, enabled));
  }

  public ManualRaidMap setLootTableIn(ManualRaidMap map, CuboidBounds selection,
      String lootTable) {
    MapIds.requireValid(lootTable, "lootTable");
    return batchReplace(map, selection, null, object -> switch (object) {
      case LootPointDefinition value -> new LootPointDefinition(value.id(), value.position(),
          value.containerType(), lootTable, value.tier(), value.virtualContainer(),
          value.enabled());
      case LootZoneDefinition value -> new LootZoneDefinition(value.id(), value.bounds(),
          lootTable, value.tier(), value.density(), value.danger(), value.enabled());
      default -> object;
    });
  }

  public ManualRaidMap setTierIn(ManualRaidMap map, CuboidBounds selection, int tier) {
    Preconditions.checkArgument(tier >= 1 && tier <= 4, "tier must be 1..4");
    return batchReplace(map, selection, null, object -> switch (object) {
      case LootPointDefinition value -> new LootPointDefinition(value.id(), value.position(),
          value.containerType(), value.lootTable(), tier, value.virtualContainer(),
          value.enabled());
      case LootZoneDefinition value -> new LootZoneDefinition(value.id(), value.bounds(),
          value.lootTable(), tier, value.density(), value.danger(), value.enabled());
      case PoiDefinition value -> new PoiDefinition(value.id(), value.displayName(),
          value.bounds(), tier, value.enabled());
      default -> object;
    });
  }

  public ManualRaidMap removeIn(ManualRaidMap map, CuboidBounds selection,
      @Nullable MapObjectType type) {
    Preconditions.checkNotNull(map, "map");
    Preconditions.checkNotNull(selection, "selection");
    ManualRaidMap result = map;
    for (MapObject object : map.objects()) {
      if ((type == null || object.type() == type) && intersects(object, selection)) {
        if (result.findObject(object.id()).isPresent()) result = remove(result, object.id());
      }
    }
    return result;
  }

  public List<MapObject> objectsIn(ManualRaidMap map, CuboidBounds selection,
      @Nullable MapObjectType type) {
    Preconditions.checkNotNull(map, "map");
    Preconditions.checkNotNull(selection, "selection");
    return map.objects().stream()
        .filter(object -> type == null || object.type() == type)
        .filter(object -> intersects(object, selection))
        .toList();
  }

  private ManualRaidMap batchReplace(ManualRaidMap map, CuboidBounds selection,
      @Nullable MapObjectType type, java.util.function.UnaryOperator<MapObject> operation) {
    Preconditions.checkNotNull(map, "map");
    Preconditions.checkNotNull(selection, "selection");
    ManualRaidMap result = map;
    for (MapObject object : map.objects()) {
      if ((type == null || object.type() == type) && intersects(object, selection)) {
        MapObject updated = Preconditions.checkNotNull(operation.apply(object), "updated object");
        if (!updated.equals(object)) result = replace(result, updated);
      }
    }
    return result;
  }

  private static boolean intersects(MapObject object, CuboidBounds selection) {
    return object.region().map(selection::intersects).orElseGet(() ->
        selection.contains(object.anchor()));
  }

  private static MapObject withEnabled(MapObject object, boolean enabled) {
    return switch (object) {
      case SpawnDefinition value -> new SpawnDefinition(value.id(), value.position(), value.yaw(),
          value.pitch(), value.team(), value.weight(), enabled, value.spawnRegion());
      case LootPointDefinition value -> new LootPointDefinition(value.id(), value.position(),
          value.containerType(), value.lootTable(), value.tier(), value.virtualContainer(), enabled);
      case LootZoneDefinition value -> new LootZoneDefinition(value.id(), value.bounds(),
          value.lootTable(), value.tier(), value.density(), value.danger(), enabled);
      case AiZoneDefinition value -> new AiZoneDefinition(value.id(), value.bounds(),
          value.aiType(), value.minimum(), value.maximum(), value.respawnSeconds(),
          value.difficulty(), value.team(), enabled);
      case ExtractionDefinition value -> new ExtractionDefinition(value.id(),
          value.displayName(), value.bounds(), value.extractionType(), value.durationSeconds(),
          value.conditionValue(), enabled);
      case PoiDefinition value -> new PoiDefinition(value.id(), value.displayName(),
          value.bounds(), value.tier(), enabled);
    };
  }

  private static ManualRaidMap mutate(ManualRaidMap map, MapObject object, boolean replace) {
    List<SpawnDefinition> spawns = update(map.spawns(), object, replace, SpawnDefinition.class);
    List<LootPointDefinition> loot = update(map.lootPoints(), object, replace,
        LootPointDefinition.class);
    List<LootZoneDefinition> lootZones = update(map.lootZones(), object, replace,
        LootZoneDefinition.class);
    List<AiZoneDefinition> ai = update(map.aiZones(), object, replace, AiZoneDefinition.class);
    List<ExtractionDefinition> extractions = update(map.extractions(), object, replace,
        ExtractionDefinition.class);
    List<PoiDefinition> pois = update(map.pois(), object, replace, PoiDefinition.class);
    return map.withObjects(spawns, loot, lootZones, ai, extractions, pois);
  }

  private static <T extends MapObject> List<T> update(List<T> source, MapObject object,
      boolean replace, Class<T> type) {
    if (!type.isInstance(object)) return source;
    T typed = type.cast(object);
    List<T> result = new ArrayList<>(source);
    if (replace) {
      for (int index = 0; index < result.size(); index++) {
        if (result.get(index).id().equals(typed.id())) {
          result.set(index, typed);
          return List.copyOf(result);
        }
      }
      throw new IllegalArgumentException("Unknown map object id: " + typed.id());
    }
    result.add(typed);
    return List.copyOf(result);
  }

  private static <T extends MapObject> List<T> without(List<T> source, String id) {
    return source.stream().filter(object -> !object.id().equals(id)).toList();
  }

  private static MapObject translated(MapObject object, String newId, int dx, int dy, int dz) {
    return switch (object) {
      case SpawnDefinition value -> new SpawnDefinition(newId,
          value.position().add(dx, dy, dz), value.yaw(), value.pitch(), value.team(),
          value.weight(), value.enabled(), value.spawnRegion() == null ? null
              : value.spawnRegion().translate(dx, dy, dz));
      case LootPointDefinition value -> new LootPointDefinition(newId,
          value.position().add(dx, dy, dz), value.containerType(), value.lootTable(),
          value.tier(), value.virtualContainer(), value.enabled());
      case LootZoneDefinition value -> new LootZoneDefinition(newId,
          value.bounds().translate(dx, dy, dz), value.lootTable(), value.tier(),
          value.density(), value.danger(), value.enabled());
      case AiZoneDefinition value -> new AiZoneDefinition(newId,
          value.bounds().translate(dx, dy, dz), value.aiType(), value.minimum(),
          value.maximum(), value.respawnSeconds(), value.difficulty(), value.team(),
          value.enabled());
      case ExtractionDefinition value -> new ExtractionDefinition(newId, value.displayName(),
          value.bounds().translate(dx, dy, dz), value.extractionType(), value.durationSeconds(),
          value.conditionValue(), value.enabled());
      case PoiDefinition value -> new PoiDefinition(newId, value.displayName(),
          value.bounds().translate(dx, dy, dz), value.tier(), value.enabled());
    };
  }
}
