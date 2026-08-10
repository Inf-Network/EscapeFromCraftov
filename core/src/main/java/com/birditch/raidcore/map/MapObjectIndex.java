package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Immutable chunk-bucket index used by editor visualization and nearby queries.
public final class MapObjectIndex {

  private static final int CHUNK_SIZE = 16;

  private final Map<Long, List<MapObject>> buckets;

  public MapObjectIndex(ManualRaidMap map) {
    Preconditions.checkNotNull(map, "map");
    Map<Long, List<MapObject>> mutable = new LinkedHashMap<>();
    for (MapObject object : map.objects()) {
      CuboidBounds bounds = object.region().orElseGet(() -> pointBounds(object.anchor()));
      int minChunkX = Math.floorDiv(bounds.minX(), CHUNK_SIZE);
      int maxChunkX = Math.floorDiv(bounds.maxX(), CHUNK_SIZE);
      int minChunkZ = Math.floorDiv(bounds.minZ(), CHUNK_SIZE);
      int maxChunkZ = Math.floorDiv(bounds.maxZ(), CHUNK_SIZE);
      for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
          mutable.computeIfAbsent(key(chunkX, chunkZ), _ -> new ArrayList<>()).add(object);
        }
      }
    }
    Map<Long, List<MapObject>> immutable = new LinkedHashMap<>();
    mutable.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
    buckets = Map.copyOf(immutable);
  }

  public List<MapObject> nearby(BlockPosition center, int radius, Set<MapObjectType> types) {
    Preconditions.checkNotNull(center, "center");
    Preconditions.checkArgument(radius >= 0, "radius must not be negative");
    Preconditions.checkNotNull(types, "types");
    if (types.isEmpty()) return List.of();

    CuboidBounds query = new CuboidBounds(center.x() - radius, center.y() - radius,
        center.z() - radius, center.x() + radius, center.y() + radius, center.z() + radius);
    int minChunkX = Math.floorDiv(query.minX(), CHUNK_SIZE);
    int maxChunkX = Math.floorDiv(query.maxX(), CHUNK_SIZE);
    int minChunkZ = Math.floorDiv(query.minZ(), CHUNK_SIZE);
    int maxChunkZ = Math.floorDiv(query.maxZ(), CHUNK_SIZE);
    double radiusSquared = (double) radius * radius;
    Set<MapObject> candidates = new LinkedHashSet<>();
    for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
      for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
        candidates.addAll(buckets.getOrDefault(key(chunkX, chunkZ), List.of()));
      }
    }
    return candidates.stream()
        .filter(object -> types.contains(object.type()))
        .filter(object -> distanceSquared(center, object) <= radiusSquared)
        .sorted(Comparator.comparingDouble((MapObject object) -> distanceSquared(center, object))
            .thenComparing(MapObject::id))
        .toList();
  }

  public List<MapObject> nearby(BlockPosition center, int radius) {
    return nearby(center, radius, EnumSet.allOf(MapObjectType.class));
  }

  int bucketCount() {
    return buckets.size();
  }

  /** Exact squared distance to a point or the nearest edge of a region object. */
  public static double distanceSquared(BlockPosition center, MapObject object) {
    Preconditions.checkNotNull(center, "center");
    Preconditions.checkNotNull(object, "object");
    return distanceSquared(center,
        object.region().orElseGet(() -> pointBounds(object.anchor())));
  }

  private static double distanceSquared(BlockPosition point, CuboidBounds bounds) {
    long dx = axisDistance(point.x(), bounds.minX(), bounds.maxX());
    long dy = axisDistance(point.y(), bounds.minY(), bounds.maxY());
    long dz = axisDistance(point.z(), bounds.minZ(), bounds.maxZ());
    return (double) dx * dx + (double) dy * dy + (double) dz * dz;
  }

  private static long axisDistance(int value, int minimum, int maximum) {
    if (value < minimum) return (long) minimum - value;
    if (value > maximum) return (long) value - maximum;
    return 0;
  }

  private static CuboidBounds pointBounds(BlockPosition point) {
    return new CuboidBounds(point.x(), point.y(), point.z(), point.x(), point.y(), point.z());
  }

  private static long key(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
  }
}
