package com.birditch.raidcore.automap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// Generates risk-weighted zones and virtual/physical loot points.
public final class LootGenerator {

  public Result generate(List<TerrainSample> samples, MapBounds bounds,
      List<PointOfInterest> pois, AutoSetupSettings.LootSettings settings, long seed) {
    List<LootZone> zones = new ArrayList<>();
    for (int index = 0; index < pois.size(); index++) {
      PointOfInterest poi = pois.get(index);
      LootTier tier = tier(poi.type());
      double density = settings.density() * switch (tier) {
        case TIER_1 -> 0.5;
        case TIER_2 -> 1.0;
        case TIER_3 -> 1.5;
        case TIER_4 -> 1.8;
      };
      zones.add(new LootZone(String.format("loot-zone-%02d", index + 1), poi.center(),
          Math.max(16, poi.radius()), tier, "raidcore:auto_" + tier.name().toLowerCase(),
          density, Math.min(1, 0.2 + tier.ordinal() * 0.25)));
    }

    List<TerrainSample> candidates = samples.stream().filter(TerrainSample::safe)
        .filter(sample -> sample.type() == TerrainType.BUILDING
            || sample.type() == TerrainType.UNDERGROUND
            || sample.type() == TerrainType.ROAD_LIKE
            || sample.existingContainer())
        .sorted(Comparator.comparingDouble((TerrainSample sample) ->
            lootScore(sample, zones) + jitter(seed, sample.point()) * 0.08).reversed()
            .thenComparingInt(sample -> sample.point().x())
            .thenComparingInt(sample -> sample.point().z()))
        .toList();
    if (candidates.isEmpty()) candidates = samples.stream().filter(TerrainSample::safe).toList();
    int target = Math.min(settings.maximumPoints(), Math.max(zones.size() * 4,
        (int) Math.round(bounds.area() / 20_000.0 * 12 * settings.density())));
    List<LootContainerPoint> points = new ArrayList<>();
    for (TerrainSample candidate : candidates) {
      if (points.stream().anyMatch(point -> point.location().horizontalDistance(candidate.point())
          < settings.minimumDistance())) continue;
      LootZone zone = nearestZone(candidate.point(), zones);
      LootTier tier = zone == null ? LootTier.TIER_1 : zone.tier();
      points.add(new LootContainerPoint(String.format("loot-%03d", points.size() + 1),
          candidate.point(), tier, !candidate.existingContainer()));
      if (points.size() >= target) break;
    }
    return new Result(zones, points);
  }

  private static double lootScore(TerrainSample sample, List<LootZone> zones) {
    double score = sample.structureScore() * 0.6 + (sample.existingContainer() ? 0.5 : 0);
    LootZone nearest = nearestZone(sample.point(), zones);
    if (nearest != null && nearest.center().horizontalDistance(sample.point()) <= nearest.radius()) {
      score += nearest.density() * 0.25;
    }
    return score;
  }

  private static LootZone nearestZone(MapPoint point, List<LootZone> zones) {
    return zones.stream().min(Comparator.comparingDouble(zone ->
        zone.center().horizontalDistance(point))).orElse(null);
  }

  private static LootTier tier(PoiType type) {
    return switch (type) {
      case LOW_VALUE -> LootTier.TIER_1;
      case MEDIUM_VALUE -> LootTier.TIER_2;
      case HIGH_VALUE -> LootTier.TIER_3;
      case SPECIAL -> LootTier.TIER_4;
    };
  }

  private static double jitter(long seed, MapPoint point) {
    long value = seed ^ 0x1007cafeL;
    value ^= (long) point.x() * 0x9E3779B97F4A7C15L;
    value ^= (long) point.z() * 0x94D049BB133111EBL;
    value ^= value >>> 31;
    value *= 0x9E3779B97F4A7C15L;
    value ^= value >>> 28;
    return (value >>> 11) * 0x1.0p-53;
  }

  public record Result(List<LootZone> zones, List<LootContainerPoint> points) {
    public Result {
      zones = List.copyOf(zones);
      points = List.copyOf(points);
    }
  }
}
