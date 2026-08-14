package com.birditch.escapefromcraftov.map;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.api.game.CraftovSpawnTeamRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Deterministic structural and reference validation for authored maps.
public final class ManualMapValidator {

  public ManualMapValidationResult validate(ManualCraftovMap map, ManualMapReferences references,
      ManualMapValidationSettings settings) {
    Preconditions.checkNotNull(map, "map");
    Preconditions.checkNotNull(references, "references");
    Preconditions.checkNotNull(settings, "settings");
    List<MapValidationIssue> issues = new ArrayList<>();
    if (!references.worldExists()) {
      error(issues, "world_missing", null, "世界未加载或不存在: " + map.worldName());
    }
    CuboidBounds bounds = map.bounds();
    if (bounds == null) {
      error(issues, "bounds_missing", null, "地图尚未设置可游玩边界");
    }

    validateIds(map, issues);
    validateCounts(map, settings, issues);
    validateBounds(map, bounds, issues);
    validateReferences(map, references, issues);
    validateSpawnTeams(map, issues);
    validateDistances(map, settings, issues);
    validateOverlaps(map, issues);

    long errors = issues.stream().filter(issue ->
        issue.severity() == MapValidationSeverity.ERROR).count();
    long warnings = issues.stream().filter(issue ->
        issue.severity() == MapValidationSeverity.WARNING).count();
    int score = (int) Math.max(0, 100 - errors * 20 - warnings * 4);
    return new ManualMapValidationResult(issues, score);
  }

  private static void validateSpawnTeams(ManualCraftovMap map, List<MapValidationIssue> issues) {
    for (SpawnDefinition spawn : map.spawns()) {
      if (spawn.enabled() && !CraftovSpawnTeamRules.isValid(spawn.team())) {
        error(issues, "spawn_team_invalid", spawn.id(),
            "出生点队伍必须是 players、any、red、blue 或 team<number>: " + spawn.team());
      }
    }
  }

  private static void validateIds(ManualCraftovMap map, List<MapValidationIssue> issues) {
    Map<String, Integer> counts = new HashMap<>();
    map.objects().forEach(object -> counts.merge(object.id(), 1, Integer::sum));
    counts.forEach((id, count) -> {
      if (count > 1) error(issues, "duplicate_id", id,
          "对象 ID 在地图内重复: " + id);
    });
  }

  private static void validateCounts(ManualCraftovMap map, ManualMapValidationSettings settings,
      List<MapValidationIssue> issues) {
    long spawnCount = map.spawns().stream().filter(SpawnDefinition::enabled).count();
    if (spawnCount < settings.minimumSpawns()) {
      error(issues, "spawns_insufficient", null,
          "启用的出生点不足，需要至少 " + settings.minimumSpawns() + " 个，当前 " + spawnCount);
    }
    long extractionCount = map.extractions().stream().filter(ExtractionDefinition::enabled).count();
    if (extractionCount < settings.minimumExtractions()) {
      error(issues, "extractions_insufficient", null,
          "启用的撤离点不足，需要至少 " + settings.minimumExtractions() + " 个，当前 "
              + extractionCount);
    }
    if (map.extractions().stream().filter(ExtractionDefinition::enabled)
        .noneMatch(value -> value.extractionType() == ManualExtractionType.NORMAL
            && value.conditions().isEmpty())) {
      error(issues, "normal_extraction_missing", null, "至少需要一个启用且无附加条件的普通撤离点");
    }
    if (map.lootPoints().stream().noneMatch(LootPointDefinition::enabled)
        && map.lootZones().stream().noneMatch(LootZoneDefinition::enabled)) {
      warning(issues, "loot_missing", null, "地图没有启用的 Loot 点或物资区域");
    }
    warnWhenBelow(issues, "loot_zones_insufficient", "物资区域",
        map.lootZones().stream().filter(LootZoneDefinition::enabled).count(),
        settings.minimumLootZones());
    warnWhenBelow(issues, "ai_zones_insufficient", "AI 区域",
        map.aiZones().stream().filter(AiZoneDefinition::enabled).count(),
        settings.minimumAiZones());
    warnWhenBelow(issues, "pois_insufficient", "兴趣点",
        map.pois().stream().filter(PoiDefinition::enabled).count(), settings.minimumPois());
  }

  private static void validateBounds(ManualCraftovMap map, CuboidBounds bounds,
      List<MapValidationIssue> issues) {
    if (bounds == null) return;
    for (MapObject object : map.objects()) {
      if (!object.enabled()) continue;
      boolean inside = object.region().map(bounds::contains).orElseGet(() ->
          bounds.contains(object.anchor()));
      if (!inside) error(issues, "outside_bounds", object.id(),
          "对象超出地图边界: " + object.id());
    }
    for (ExtractionDefinition extraction : map.extractions()) {
      if (!extraction.enabled()) continue;
      for (ExtractionRuleDefinition rule : extraction.conditions()) {
        if (rule instanceof ExtractionRuleDefinition.SwitchActivated lever
            && !bounds.contains(lever.position())) {
          error(issues, "extraction_switch_outside_bounds", extraction.id(),
              "撤离拉闸位置超出地图边界: " + lever.switchId());
        }
      }
    }
  }

  private static void validateReferences(ManualCraftovMap map, ManualMapReferences references,
      List<MapValidationIssue> issues) {
    for (LootPointDefinition loot : map.lootPoints()) {
      if (loot.enabled() && !references.lootTables().contains(loot.lootTable())) {
        error(issues, "loot_table_missing", loot.id(),
            "LootTable 不存在: " + loot.lootTable());
      }
    }
    for (LootZoneDefinition zone : map.lootZones()) {
      if (zone.enabled() && !references.lootTables().contains(zone.lootTable())) {
        error(issues, "loot_table_missing", zone.id(),
            "LootTable 不存在: " + zone.lootTable());
      }
    }
    for (ExtractionDefinition extraction : map.extractions()) {
      if (!extraction.enabled()) continue;
      String condition = extraction.conditionValue();
      switch (extraction.extractionType()) {
        case NORMAL -> {
          if (condition != null && !condition.isBlank()) warning(issues,
              "normal_condition_ignored", extraction.id(), "普通撤离点的条件值会被忽略");
        }
        case ITEM_REQUIRED -> {
          if (!references.itemIds().contains(condition)) error(issues,
              "required_item_missing", extraction.id(), "条件撤离物品不存在: " + condition);
        }
        case PAID, TIMED -> {
          if (!isNonNegativeLong(condition)) error(issues, "condition_invalid", extraction.id(),
              "条件值必须是非负整数: " + condition);
        }
      }
      Set<ExtractionRuleDefinition.RuleType> unique = new HashSet<>();
      Set<String> switches = new HashSet<>();
      for (ExtractionRuleDefinition rule : extraction.conditions()) {
        if (rule instanceof ExtractionRuleDefinition.SwitchActivated lever) {
          if (!switches.add(lever.switchId())) {
            error(issues, "extraction_switch_duplicate", extraction.id(),
                "撤离点重复引用拉闸 ID: " + lever.switchId());
          }
          continue;
        }
        if (!unique.add(rule.type())) {
          error(issues, "extraction_condition_duplicate", extraction.id(),
              "撤离点的组合条件重复: " + rule.type());
        }
        if (rule instanceof ExtractionRuleDefinition.RandomOpen random
            && random.chance() == 0) {
          warning(issues, "random_extraction_never_opens", extraction.id(),
              "随机撤离点开放概率为 0，本局永远无法使用");
        }
      }
    }
  }

  private static void validateDistances(ManualCraftovMap map, ManualMapValidationSettings settings,
      List<MapValidationIssue> issues) {
    List<SpawnDefinition> spawns = map.spawns().stream().filter(SpawnDefinition::enabled).toList();
    for (int first = 0; first < spawns.size(); first++) {
      for (int second = first + 1; second < spawns.size(); second++) {
        double distance = spawns.get(first).position().horizontalDistance(
            spawns.get(second).position());
        if (distance < settings.minimumSpawnDistance()) {
          warning(issues, "spawns_too_close", spawns.get(second).id(),
              "出生点距离过近: " + spawns.get(first).id() + " / " + spawns.get(second).id());
        }
      }
    }
    for (SpawnDefinition spawn : spawns) {
      for (ExtractionDefinition extraction : map.extractions()) {
        if (!extraction.enabled()) continue;
        if (spawn.position().horizontalDistance(extraction.anchor())
            < settings.minimumSpawnExtractionDistance()) {
          error(issues, "spawn_extraction_too_close", extraction.id(),
              "撤离点离出生点过近: " + spawn.id() + " / " + extraction.id());
        }
      }
    }
  }

  private static void validateOverlaps(ManualCraftovMap map, List<MapValidationIssue> issues) {
    Set<BlockPosition> lootPositions = new HashSet<>();
    for (LootPointDefinition loot : map.lootPoints()) {
      if (loot.enabled() && !lootPositions.add(loot.position())) {
        warning(issues, "loot_overlap", loot.id(), "多个 Loot 点位于同一方块");
      }
    }
    List<ExtractionDefinition> extractions = map.extractions().stream()
        .filter(ExtractionDefinition::enabled).toList();
    for (int first = 0; first < extractions.size(); first++) {
      for (int second = first + 1; second < extractions.size(); second++) {
        if (extractions.get(first).bounds().intersects(extractions.get(second).bounds())) {
          warning(issues, "extraction_overlap", extractions.get(second).id(),
              "撤离区域互相重叠: " + extractions.get(first).id() + " / "
                  + extractions.get(second).id());
        }
      }
    }
  }

  private static boolean isNonNegativeLong(String value) {
    try {
      return value != null && Long.parseLong(value) >= 0;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private static void warnWhenBelow(List<MapValidationIssue> issues, String code,
      String label, long actual, int minimum) {
    if (actual < minimum) {
      warning(issues, code, null,
          "启用的" + label + "不足，建议至少 " + minimum + " 个，当前 " + actual);
    }
  }

  private static void error(List<MapValidationIssue> issues, String code, String objectId,
      String message) {
    issues.add(new MapValidationIssue(MapValidationSeverity.ERROR, code, objectId, message));
  }

  private static void warning(List<MapValidationIssue> issues, String code, String objectId,
      String message) {
    issues.add(new MapValidationIssue(MapValidationSeverity.WARNING, code, objectId, message));
  }
}
