package com.birditch.raidcore.automap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// Selects safe edge exits with cardinal spread and spawn-distance checks.
public final class ExtractionGenerator {

  public List<ExtractionRegion> generate(List<TerrainSample> samples, MapBounds bounds,
      List<SpawnRegion> spawns, AutoSetupSettings.ExtractionSettings settings, long seed) {
    int normalTarget = Math.min(settings.normalMaximum(), settings.normalMinimum()
        + (Math.min(bounds.width(), bounds.depth()) >= 1000 ? 1 : 0));
    int conditionalTarget = Math.min(settings.conditionalMaximum(), settings.conditionalMinimum()
        + (Math.min(bounds.width(), bounds.depth()) >= 1400 ? 1 : 0));
    int total = normalTarget + conditionalTarget;
    List<TerrainSample> candidates = samples.stream().filter(TerrainSample::safe)
        .filter(sample -> spawns.stream().allMatch(spawn ->
            spawn.center().horizontalDistance(sample.point()) >= settings.minimumSpawnDistance()))
        .sorted(Comparator.comparingDouble((TerrainSample sample) ->
            bounds.edgeDistance(sample.point().x(), sample.point().z())
                + jitter(seed, sample.point()) * 2.0)
            .thenComparingInt(sample -> sample.point().x())
            .thenComparingInt(sample -> sample.point().z()))
        .toList();
    List<MapPoint> selected = new ArrayList<>();
    for (int sector = 0; sector < 4 && selected.size() < total; sector++) {
      int currentSector = sector;
      candidates.stream().filter(sample -> sector(sample.point(), bounds) == currentSector)
          .filter(sample -> separated(selected, sample.point(), settings.minimumDistance()))
          .findFirst().ifPresent(sample -> selected.add(sample.point()));
    }
    for (TerrainSample candidate : candidates) {
      if (selected.size() >= total) break;
      if (separated(selected, candidate.point(), settings.minimumDistance())) {
        selected.add(candidate.point());
      }
    }
    List<ExtractionRegion> result = new ArrayList<>();
    for (int index = 0; index < selected.size(); index++) {
      boolean normal = index < normalTarget;
      AutoExtractionType type = normal ? AutoExtractionType.NORMAL
          : switch ((index - normalTarget) % 3) {
            case 0 -> AutoExtractionType.ITEM_REQUIRED;
            case 1 -> AutoExtractionType.TIMED;
            default -> AutoExtractionType.PAID;
          };
      String condition = switch (type) {
        case NORMAL -> null;
        case ITEM_REQUIRED -> "raidcore:extraction_key";
        case TIMED -> "600";
        case PAID -> "500";
      };
      MapPoint point = selected.get(index);
      result.add(new ExtractionRegion(String.format("extract-%02d", index + 1),
          name(point, bounds), point, settings.regionRadius(), type, condition));
    }
    return List.copyOf(result);
  }

  private static boolean separated(List<MapPoint> points, MapPoint candidate, int distance) {
    return points.stream().allMatch(point -> point.horizontalDistance(candidate) >= distance);
  }

  private static int sector(MapPoint point, MapBounds bounds) {
    int dx = point.x() - bounds.centerX();
    int dz = point.z() - bounds.centerZ();
    if (Math.abs(dx) > Math.abs(dz)) return dx >= 0 ? 1 : 3;
    return dz >= 0 ? 2 : 0;
  }

  private static String name(MapPoint point, MapBounds bounds) {
    return switch (sector(point, bounds)) {
      case 0 -> "北部撤离点";
      case 1 -> "东部出口";
      case 2 -> "南部撤离点";
      default -> "西部出口";
    };
  }

  private static double jitter(long seed, MapPoint point) {
    long value = seed ^ 0xe17ac710L;
    value ^= (long) point.x() * 0x9E3779B97F4A7C15L;
    value ^= (long) point.z() * 0xC2B2AE3D27D4EB4FL;
    value ^= value >>> 30;
    value *= 0xbf58476d1ce4e5b9L;
    value ^= value >>> 27;
    return (value >>> 11) * 0x1.0p-53;
  }
}
