package com.birditch.escapefromcraftov.automap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// Greedy, deterministic spawn-region distribution biased toward safe edges.
public final class SpawnGenerator {

  public List<SpawnRegion> generate(List<TerrainSample> samples, MapBounds bounds,
      List<PointOfInterest> pois, AutoSetupSettings.SpawnSettings settings, long seed) {
    List<TerrainSample> candidates = samples.stream()
        .filter(TerrainSample::safe)
        .filter(sample -> sample.type() != TerrainType.BUILDING
            && sample.type() != TerrainType.UNDERGROUND)
        .filter(sample -> pois.stream().filter(poi -> poi.type() == PoiType.HIGH_VALUE)
            .allMatch(poi -> poi.center().horizontalDistance(sample.point()) > poi.radius()))
        .sorted(Comparator.comparingDouble((TerrainSample sample) ->
                candidateScore(sample, bounds) + jitter(seed, sample.point()) * 0.05).reversed()
            .thenComparingInt(sample -> sample.point().x())
            .thenComparingInt(sample -> sample.point().z()))
        .toList();
    List<MapPoint> chosen = chooseSeparated(candidates, settings.maximum(),
        settings.minimumDistance(), settings.minimum());
    List<SpawnRegion> result = new ArrayList<>();
    for (int index = 0; index < chosen.size(); index++) {
      result.add(new SpawnRegion(String.format("spawn-%02d", index + 1), chosen.get(index),
          settings.regionRadius()));
    }
    return List.copyOf(result);
  }

  private static List<MapPoint> chooseSeparated(List<TerrainSample> candidates, int maximum,
      int requestedDistance, int minimum) {
    int distance = requestedDistance;
    while (distance >= 16) {
      int currentDistance = distance;
      List<MapPoint> selected = new ArrayList<>();
      for (TerrainSample candidate : candidates) {
        if (selected.stream().allMatch(point ->
            point.horizontalDistance(candidate.point()) >= currentDistance)) {
          selected.add(candidate.point());
          if (selected.size() >= maximum) break;
        }
      }
      if (selected.size() >= minimum || distance == 16) return selected;
      distance = Math.max(16, (int) (distance * 0.8));
    }
    return List.of();
  }

  private static double candidateScore(TerrainSample sample, MapBounds bounds) {
    double maxEdge = Math.min(bounds.width(), bounds.depth()) / 2.0;
    double edgeBias = 1.0 - Math.min(1.0, bounds.edgeDistance(
        sample.point().x(), sample.point().z()) / maxEdge);
    return edgeBias * 0.7 + sample.opennessScore() * 0.3;
  }

  private static double jitter(long seed, MapPoint point) {
    long value = seed ^ 0x51a7c0deL;
    value ^= (long) point.x() * 0x9E3779B97F4A7C15L;
    value ^= (long) point.z() * 0xC2B2AE3D27D4EB4FL;
    value ^= value >>> 33;
    value *= 0xff51afd7ed558ccdL;
    value ^= value >>> 33;
    return (value >>> 11) * 0x1.0p-53;
  }
}
