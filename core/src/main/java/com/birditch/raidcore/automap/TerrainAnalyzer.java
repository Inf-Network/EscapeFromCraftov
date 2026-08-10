package com.birditch.raidcore.automap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Converts raw observations into reproducible terrain classifications.
public final class TerrainAnalyzer {

  public List<TerrainSample> analyze(MapSnapshot snapshot) {
    Map<Long, RawTerrainSample> grid = new HashMap<>();
    snapshot.samples().forEach(sample -> grid.put(key(sample.x(), sample.z()), sample));
    List<TerrainSample> result = new ArrayList<>(snapshot.samples().size());
    for (RawTerrainSample raw : snapshot.samples()) {
      int slope = maximumNeighborDelta(raw, grid, snapshot.sampleStep());
      double structure = clamp(raw.constructedRatio() * 0.7 + raw.roofCoverage() * 0.3);
      double openness = clamp(1.0 - raw.roofCoverage() - raw.treeRatio() * 0.45);
      TerrainType type = classify(raw, slope, structure, openness);
      boolean safe = raw.safeGround() && !raw.water() && !raw.lava() && slope <= 5;
      result.add(new TerrainSample(new MapPoint(raw.x(), raw.y(), raw.z()), type, safe,
          raw.existingContainer(), structure, openness));
    }
    return List.copyOf(result);
  }

  private static TerrainType classify(
      RawTerrainSample raw, int slope, double structure, double openness) {
    if (raw.water() || raw.lava()) return TerrainType.WATER;
    if (!raw.safeGround()) return TerrainType.UNKNOWN;
    if (slope > 5) return TerrainType.STEEP;
    if (raw.underground() && raw.roofCoverage() >= 0.35) return TerrainType.UNDERGROUND;
    if (structure >= 0.32 || raw.roofCoverage() >= 0.55) return TerrainType.BUILDING;
    if (raw.treeRatio() >= 0.30) return TerrainType.FOREST;
    if (structure >= 0.10 && openness >= 0.55) return TerrainType.ROAD_LIKE;
    return TerrainType.OPEN;
  }

  private static int maximumNeighborDelta(
      RawTerrainSample sample, Map<Long, RawTerrainSample> grid, int step) {
    int maximum = 0;
    int[][] offsets = {{step, 0}, {-step, 0}, {0, step}, {0, -step}};
    for (int[] offset : offsets) {
      RawTerrainSample neighbor = grid.get(key(sample.x() + offset[0], sample.z() + offset[1]));
      if (neighbor != null) {
        maximum = Math.max(maximum, Math.abs(sample.y() - neighbor.y()));
      }
    }
    return maximum;
  }

  private static long key(int x, int z) {
    return ((long) x << 32) ^ (z & 0xffffffffL);
  }

  private static double clamp(double value) {
    return Math.max(0.0, Math.min(1.0, value));
  }
}
