package com.birditch.raidcore.automap;

import java.util.List;

/// Transparent 0..100 quality score used for operator feedback, not matchmaking.
public final class MapScore {

  public int calculate(MapBounds bounds, List<SpawnRegion> spawns,
      List<LootZone> lootZones, List<LootContainerPoint> lootPoints,
      List<PointOfInterest> pois, List<ExtractionRegion> extractions) {
    int spawn = distribution(spawns.stream().map(SpawnRegion::center).toList(), bounds, 20);
    int extraction = distribution(extractions.stream().map(ExtractionRegion::center).toList(),
        bounds, 20);
    int poi = Math.min(20, pois.size() * 3);
    int loot = Math.min(20, lootZones.size() * 3 + lootPoints.size() / 20);
    int traversal = Math.min(20, (int) (Math.min(bounds.width(), bounds.depth()) / 40.0));
    return Math.min(100, spawn + extraction + poi + loot + traversal);
  }

  private static int distribution(List<MapPoint> points, MapBounds bounds, int maximum) {
    if (points.isEmpty()) return 0;
    int quadrants = (int) points.stream().map(point -> quadrant(point, bounds)).distinct().count();
    return Math.min(maximum, points.size() * 2 + quadrants * 3);
  }

  private static int quadrant(MapPoint point, MapBounds bounds) {
    int east = point.x() >= bounds.centerX() ? 1 : 0;
    int south = point.z() >= bounds.centerZ() ? 2 : 0;
    return east + south;
  }
}
