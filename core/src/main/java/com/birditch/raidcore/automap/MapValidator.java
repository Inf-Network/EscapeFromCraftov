package com.birditch.raidcore.automap;

import java.util.ArrayList;
import java.util.List;

/// Converts balance rules into explicit PASS/WARNING/ERROR operator feedback.
public final class MapValidator {

  public MapValidationReport validate(int score, MapBounds bounds, List<PointOfInterest> pois,
      List<SpawnRegion> spawns, List<LootZone> lootZones,
      List<LootContainerPoint> lootPoints, List<ExtractionRegion> extractions,
      AutoSetupSettings settings) {
    List<ValidationIssue> issues = baseIssues(score, pois, spawns, lootZones, lootPoints,
        extractions, settings.validation());
    spacing(issues, "出生区域", spawns.stream().map(SpawnRegion::center).toList(),
        settings.spawns().minimumDistance(), ValidationSeverity.ERROR);
    spacing(issues, "撤离点", extractions.stream().map(ExtractionRegion::center).toList(),
        settings.extractions().minimumDistance(), ValidationSeverity.ERROR);
    crossSpacing(issues, spawns, extractions,
        settings.extractions().minimumSpawnDistance());
    spacing(issues, "Loot 点", lootPoints.stream().map(LootContainerPoint::location).toList(),
        settings.loot().minimumDistance(), ValidationSeverity.WARNING);
    if (bounds.area() < 128L * 128L) {
      issues.add(new ValidationIssue(ValidationSeverity.WARNING,
          "地图分析面积较小(" + bounds.width() + "x" + bounds.depth() + ")"));
    } else {
      issues.add(new ValidationIssue(ValidationSeverity.PASS,
          "分析区域 " + bounds.width() + "x" + bounds.depth()));
    }
    return new MapValidationReport(issues);
  }

  public MapValidationReport validate(int score, List<PointOfInterest> pois,
      List<SpawnRegion> spawns, List<LootZone> lootZones,
      List<LootContainerPoint> lootPoints, List<ExtractionRegion> extractions,
      AutoSetupSettings.ValidationSettings settings) {
    return new MapValidationReport(baseIssues(score, pois, spawns, lootZones, lootPoints,
        extractions, settings));
  }

  private static List<ValidationIssue> baseIssues(int score, List<PointOfInterest> pois,
      List<SpawnRegion> spawns, List<LootZone> lootZones,
      List<LootContainerPoint> lootPoints, List<ExtractionRegion> extractions,
      AutoSetupSettings.ValidationSettings settings) {
    List<ValidationIssue> issues = new ArrayList<>();
    count(issues, "出生区域", spawns.size(), settings.minimumSpawns());
    count(issues, "Loot Zone", lootZones.size(), settings.minimumLootZones());
    count(issues, "撤离点", extractions.size(), settings.minimumExtractions());
    count(issues, "POI", pois.size(), 1);
    if (lootPoints.size() < 10) {
      issues.add(new ValidationIssue(ValidationSeverity.WARNING,
          "Loot 点较少(" + lootPoints.size() + "),建议 Preview 后调整密度"));
    } else {
      issues.add(new ValidationIssue(ValidationSeverity.PASS,
          "已生成 " + lootPoints.size() + " 个 Loot 点"));
    }
    if (score < settings.minimumScore()) {
      issues.add(new ValidationIssue(ValidationSeverity.ERROR,
          "地图评分 " + score + " 低于最低要求 " + settings.minimumScore()));
    } else {
      issues.add(new ValidationIssue(ValidationSeverity.PASS, "地图评分 " + score + "/100"));
    }
    return issues;
  }

  private static void count(List<ValidationIssue> issues, String name, int actual, int minimum) {
    ValidationSeverity severity = actual >= minimum ? ValidationSeverity.PASS
        : ValidationSeverity.ERROR;
    issues.add(new ValidationIssue(severity,
        name + ":" + actual + " (最低 " + minimum + ")"));
  }

  private static void spacing(List<ValidationIssue> issues, String name, List<MapPoint> points,
      int minimum, ValidationSeverity failureSeverity) {
    double closest = Double.POSITIVE_INFINITY;
    for (int first = 0; first < points.size(); first++) {
      for (int second = first + 1; second < points.size(); second++) {
        closest = Math.min(closest, points.get(first).horizontalDistance(points.get(second)));
      }
    }
    if (Double.isInfinite(closest)) return;
    int rounded = (int) Math.floor(closest);
    if (closest < minimum) {
      issues.add(new ValidationIssue(failureSeverity,
          name + "最近距离 " + rounded + "，低于要求 " + minimum));
    } else {
      issues.add(new ValidationIssue(ValidationSeverity.PASS,
          name + "最近距离 " + rounded + " (要求 " + minimum + ")"));
    }
  }

  private static void crossSpacing(List<ValidationIssue> issues, List<SpawnRegion> spawns,
      List<ExtractionRegion> extractions, int minimum) {
    double closest = Double.POSITIVE_INFINITY;
    for (SpawnRegion spawn : spawns) {
      for (ExtractionRegion extraction : extractions) {
        closest = Math.min(closest,
            spawn.center().horizontalDistance(extraction.center()));
      }
    }
    if (Double.isInfinite(closest)) return;
    int rounded = (int) Math.floor(closest);
    if (closest < minimum) {
      issues.add(new ValidationIssue(ValidationSeverity.ERROR,
          "出生区域到撤离点最近距离 " + rounded + "，低于要求 " + minimum));
    } else {
      issues.add(new ValidationIssue(ValidationSeverity.PASS,
          "出生区域到撤离点最近距离 " + rounded + " (要求 " + minimum + ")"));
    }
  }
}
