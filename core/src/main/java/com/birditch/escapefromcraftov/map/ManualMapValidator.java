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
    validateAiPatrolRoutes(map, bounds, issues);
    validateReferences(map, references, issues);
    validateEvents(map, issues);
    validateSpawnTeams(map, issues);
    validateExtractionRoutes(map, issues);
    validateDistances(map, settings, issues);
    validateOverlaps(map, issues);

    long errors = issues.stream().filter(issue ->
        issue.severity() == MapValidationSeverity.ERROR).count();
    long warnings = issues.stream().filter(issue ->
        issue.severity() == MapValidationSeverity.WARNING).count();
    int score = (int) Math.max(0, 100 - errors * 20 - warnings * 4);
    return new ManualMapValidationResult(issues, score);
  }

  private static void validateExtractionRoutes(
      ManualCraftovMap map, List<MapValidationIssue> issues) {
    Set<String> knownGroups = map.spawns().stream().map(SpawnDefinition::routeGroup)
        .collect(java.util.stream.Collectors.toSet());
    Set<String> enabledGroups = map.spawns().stream().filter(SpawnDefinition::enabled)
        .map(SpawnDefinition::routeGroup).collect(java.util.stream.Collectors.toSet());
    List<ExtractionDefinition> authored = map.extractions().stream()
        .filter(value -> value.routeScope() != null).toList();
    if (authored.isEmpty()) return;
    for (ExtractionDefinition extraction : authored) {
      ExtractionRouteScope scope = extraction.routeScope();
      Set<String> unique = new HashSet<>();
      for (String group : scope.eligibleSpawnGroups()) {
        if (!unique.add(group)) {
          error(issues, "route_group_duplicate", extraction.id(),
              "撤离路线重复引用出生组: " + group);
        }
        if (!knownGroups.contains(group)) {
          error(issues, "route_group_unknown", extraction.id(),
              "撤离路线引用了未知出生组: " + group);
        }
      }
    }
    List<ExtractionDefinition> enabledAuthored = authored.stream()
        .filter(ExtractionDefinition::enabled).toList();
    for (String group : enabledGroups) {
      boolean reliable = enabledAuthored.stream().anyMatch(extraction ->
          (extraction.routeScope().global()
              || extraction.routeScope().eligibleSpawnGroups().contains(group))
              && extraction.extractionType() == ManualExtractionType.NORMAL
              && extraction.conditions().isEmpty());
      if (!reliable) {
        error(issues, "route_group_without_reliable_exit", null,
            "出生路线组没有可用的无条件普通或全局撤离点: " + group);
      }
    }
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
    Set<String> objectIds = counts.keySet();
    Set<String> eventIds = new HashSet<>();
    for (MapEventDefinition event : map.events()) {
      if (!eventIds.add(event.id())) {
        error(issues, "event_id_duplicate", event.id(),
            "地图事件 ID 重复: " + event.id());
      }
      if (objectIds.contains(event.id())) {
        error(issues, "event_id_conflict", event.id(),
            "地图事件 ID 不能与地图对象 ID 相同: " + event.id());
      }
    }
  }

  private static void validateEvents(ManualCraftovMap map, List<MapValidationIssue> issues) {
    Map<String, AiZoneDefinition> aiZones = new HashMap<>();
    for (AiZoneDefinition zone : map.aiZones()) aiZones.putIfAbsent(zone.id(), zone);
    Map<String, LootPointDefinition> lootPoints = new HashMap<>();
    for (LootPointDefinition point : map.lootPoints()) lootPoints.putIfAbsent(point.id(), point);
    Set<String> claimedAiZones = new HashSet<>();
    Set<String> claimedCaches = new HashSet<>();
    for (MapEventDefinition event : map.events()) {
      if (!event.enabled()) continue;
      AiZoneDefinition aiZone = aiZones.get(event.aiZoneId());
      if (aiZone == null) {
        error(issues, "event_ai_zone_missing", event.id(),
            "地图事件引用了不存在的 AI 区域: " + event.aiZoneId());
      } else if (!aiZone.enabled()) {
        error(issues, "event_ai_zone_disabled", event.id(),
            "地图事件引用的 AI 区域未启用: " + event.aiZoneId());
      }
      LootPointDefinition cache = lootPoints.get(event.cacheLootPointId());
      if (cache == null) {
        error(issues, "event_cache_loot_missing", event.id(),
            "地图事件引用了不存在的 Loot 点: " + event.cacheLootPointId());
      } else if (!cache.enabled()) {
        error(issues, "event_cache_loot_disabled", event.id(),
            "地图事件引用的 Loot 点未启用: " + event.cacheLootPointId());
      }
      if (!claimedAiZones.add(event.aiZoneId())) {
        error(issues, "event_ai_zone_reused", event.id(),
            "同一个 AI 区域不能被多个启用事件复用: " + event.aiZoneId());
      }
      if (!claimedCaches.add(event.cacheLootPointId())) {
        error(issues, "event_cache_loot_reused", event.id(),
            "同一个 Loot 点不能被多个启用事件复用: " + event.cacheLootPointId());
      }
      if (event.startDelaySeconds() < 0 || event.startDelaySeconds() > 3_600) {
        error(issues, "event_start_delay_invalid", event.id(),
            "地图事件延迟必须为 0..3600 秒");
      }
      if (!Double.isFinite(event.healthMultiplier()) || event.healthMultiplier() <= 0
          || event.healthMultiplier() > 20) {
        error(issues, "event_health_multiplier_invalid", event.id(),
            "地图事件生命倍率必须是有限值且在 (0, 20] 内");
      }
      if (!Double.isFinite(event.damageMultiplier()) || event.damageMultiplier() <= 0
          || event.damageMultiplier() > 8) {
        error(issues, "event_damage_multiplier_invalid", event.id(),
            "地图事件伤害倍率必须是有限值且在 (0, 8] 内");
      }
      if (event.protectionSeconds() < 0 || event.protectionSeconds() > 300) {
        error(issues, "event_protection_seconds_invalid", event.id(),
            "地图事件保护时间必须为 0..300 秒");
      }
    }
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

  private static void validateAiPatrolRoutes(ManualCraftovMap map,
      CuboidBounds mapBounds, List<MapValidationIssue> issues) {
    for (AiZoneDefinition zone : map.aiZones()) {
      if (zone.patrolWaypoints().size()
          > com.birditch.escapefromcraftov.api.game.CraftovAiZone.MAXIMUM_PATROL_WAYPOINTS) {
        error(issues, "ai_patrol_too_many", zone.id(),
            "AI 巡逻路线最多只能有 "
                + com.birditch.escapefromcraftov.api.game.CraftovAiZone
                    .MAXIMUM_PATROL_WAYPOINTS
                + " 个点");
      }
      Set<BlockPosition> unique = new HashSet<>();
      for (int index = 0; index < zone.patrolWaypoints().size(); index++) {
        BlockPosition waypoint = zone.patrolWaypoints().get(index);
        int displayIndex = index + 1;
        if (!unique.add(waypoint)) {
          error(issues, "ai_patrol_duplicate", zone.id(),
              "AI 巡逻点重复: " + zone.id() + " #" + displayIndex);
        }
        if (!zone.bounds().contains(waypoint)) {
          error(issues, "ai_patrol_outside_zone", zone.id(),
              "AI 巡逻点不在所属区域内: " + zone.id() + " #" + displayIndex);
        }
        if (mapBounds != null && !mapBounds.contains(waypoint)) {
          error(issues, "ai_patrol_outside_bounds", zone.id(),
              "AI 巡逻点超出地图边界: " + zone.id() + " #" + displayIndex);
        }
      }
    }
  }

  private static void validateReferences(ManualCraftovMap map, ManualMapReferences references,
      List<MapValidationIssue> issues) {
    Map<String, MapEventDefinition> enabledEvents = new HashMap<>();
    for (MapEventDefinition event : map.events()) {
      if (event.enabled()) enabledEvents.putIfAbsent(event.id(), event);
    }
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
        if (rule instanceof ExtractionRuleDefinition.EventCompleted event) {
          if (event.eventId().isBlank()
              || !event.eventId().matches("[a-z0-9_]+")) {
            error(issues, "extraction_event_invalid", extraction.id(),
                "撤离点引用的地图事件 ID 无效: " + event.eventId());
          } else if (!enabledEvents.containsKey(event.eventId())) {
            error(issues, "extraction_event_missing", extraction.id(),
                "撤离点引用的启用地图事件不存在: " + event.eventId());
          }
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
