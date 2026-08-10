package com.birditch.raidcore.automap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Detects connected structure clusters, then fills sparse maps with low-value
/// traversal POIs so a valid map never depends on hand-placed labels.
public final class POIGenerator {

  public List<PointOfInterest> generate(
      List<TerrainSample> samples, MapBounds bounds, int step,
      AutoSetupSettings.PoiSettings settings) {
    Map<Long, TerrainSample> candidates = new HashMap<>();
    samples.stream()
        .filter(TerrainSample::safe)
        .filter(sample -> sample.type() == TerrainType.BUILDING
            || sample.type() == TerrainType.UNDERGROUND
            || sample.structureScore() >= 0.28)
        .forEach(sample -> candidates.put(key(sample.point()), sample));
    Set<Long> visited = new HashSet<>();
    List<Cluster> clusters = new ArrayList<>();
    for (Map.Entry<Long, TerrainSample> entry : candidates.entrySet()) {
      if (visited.add(entry.getKey())) {
        clusters.add(flood(entry.getValue(), candidates, visited, step));
      }
    }
    int minimumSamples = Math.max(1, settings.minimumArea() / (step * step));
    List<Cluster> accepted = clusters.stream()
        .filter(cluster -> cluster.samples().size() >= minimumSamples)
        .sorted(Comparator.comparingDouble(Cluster::score).reversed()
            .thenComparingInt(cluster -> centroid(cluster.samples()).x())
            .thenComparingInt(cluster -> centroid(cluster.samples()).z()))
        .limit(settings.maximum())
        .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    addFallbackClusters(accepted, samples, settings.minimum(), settings.maximum());
    List<PointOfInterest> result = new ArrayList<>();
    for (int index = 0; index < accepted.size(); index++) {
      Cluster cluster = accepted.get(index);
      MapPoint center = centroid(cluster.samples());
      double score = cluster.score();
      PoiType type = score >= 0.78 ? PoiType.HIGH_VALUE
          : score >= 0.48 ? PoiType.MEDIUM_VALUE
          : cluster.underground() ? PoiType.SPECIAL : PoiType.LOW_VALUE;
      int radius = Math.max(step * 2,
          (int) Math.ceil(Math.sqrt(cluster.samples().size()) * step));
      result.add(new PointOfInterest(String.format("poi-%02d", index + 1),
          directionalName(center, bounds, index), type, center, radius, score));
    }
    return List.copyOf(result);
  }

  private static Cluster flood(TerrainSample start, Map<Long, TerrainSample> candidates,
      Set<Long> visited, int step) {
    ArrayDeque<TerrainSample> queue = new ArrayDeque<>();
    List<TerrainSample> cluster = new ArrayList<>();
    queue.add(start);
    while (!queue.isEmpty()) {
      TerrainSample sample = queue.removeFirst();
      cluster.add(sample);
      int x = sample.point().x();
      int z = sample.point().z();
      int[][] offsets = {{step, 0}, {-step, 0}, {0, step}, {0, -step}};
      for (int[] offset : offsets) {
        long key = key(x + offset[0], z + offset[1]);
        TerrainSample neighbor = candidates.get(key);
        if (neighbor != null && visited.add(key)) queue.addLast(neighbor);
      }
    }
    double structure = cluster.stream().mapToDouble(TerrainSample::structureScore).average().orElse(0);
    boolean underground = cluster.stream().anyMatch(s -> s.type() == TerrainType.UNDERGROUND);
    return new Cluster(cluster, Math.min(1, structure + Math.min(0.35, cluster.size() / 80.0)),
        underground);
  }

  private static void addFallbackClusters(List<Cluster> clusters, List<TerrainSample> samples,
      int minimum, int maximum) {
    List<TerrainSample> safe = samples.stream().filter(TerrainSample::safe)
        .sorted(Comparator.comparingDouble(TerrainSample::structureScore).reversed()
            .thenComparingInt(sample -> sample.point().x())
            .thenComparingInt(sample -> sample.point().z()))
        .toList();
    for (TerrainSample candidate : safe) {
      if (clusters.size() >= minimum || clusters.size() >= maximum) break;
      boolean separated = clusters.stream().map(cluster -> centroid(cluster.samples()))
          .allMatch(center -> center.horizontalDistance(candidate.point()) >= 64);
      if (separated) clusters.add(new Cluster(List.of(candidate), 0.25, false));
    }
  }

  private static MapPoint centroid(List<TerrainSample> samples) {
    int x = (int) Math.round(samples.stream().mapToInt(s -> s.point().x()).average().orElse(0));
    int y = (int) Math.round(samples.stream().mapToInt(s -> s.point().y()).average().orElse(0));
    int z = (int) Math.round(samples.stream().mapToInt(s -> s.point().z()).average().orElse(0));
    return new MapPoint(x, y, z);
  }

  private static String directionalName(MapPoint point, MapBounds bounds, int index) {
    int dx = point.x() - bounds.centerX();
    int dz = point.z() - bounds.centerZ();
    String direction = Math.abs(dx) > Math.abs(dz)
        ? (dx >= 0 ? "东部" : "西部") : (dz >= 0 ? "南部" : "北部");
    return direction + "区域 " + (index + 1);
  }

  private static long key(MapPoint point) { return key(point.x(), point.z()); }
  private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
  private record Cluster(List<TerrainSample> samples, double score, boolean underground) {}
}
