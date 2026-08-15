package com.birditch.escapefromcraftov.map;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable mutations for non-spatial map events.
 *
 * <p>Events deliberately do not implement {@link MapObject}: they have no anchor or region.
 * This editor is therefore kept separate from {@link ManualMapEditor}, while still returning a
 * new {@link ManualCraftovMap} so {@link EditorSession#apply} owns undo/redo and dirty state.</p>
 */
public final class ManualMapEventEditor {

  public ManualCraftovMap add(ManualCraftovMap map, MapEventDefinition event) {
    Preconditions.checkNotNull(map, "map");
    Preconditions.checkNotNull(event, "event");
    Preconditions.checkArgument(find(map, event.id()) == null,
        "地图事件 ID 已存在: " + event.id());
    Preconditions.checkArgument(map.findObject(event.id()).isEmpty(),
        "地图事件 ID 不能与地图对象 ID 相同: " + event.id());
    validateEditable(map, event, null);
    List<MapEventDefinition> events = new ArrayList<>(map.events());
    events.add(event);
    return map.withEvents(events);
  }

  public ManualCraftovMap replace(ManualCraftovMap map, MapEventDefinition event) {
    Preconditions.checkNotNull(map, "map");
    Preconditions.checkNotNull(event, "event");
    MapEventDefinition current = require(map, event.id());
    if (current.enabled() && !event.enabled()) {
      requireNoExtractionReference(map, event.id());
    }
    validateEditable(map, event, event.id());
    List<MapEventDefinition> events = new ArrayList<>(map.events());
    for (int index = 0; index < events.size(); index++) {
      if (events.get(index).id().equals(event.id())) {
        events.set(index, event);
        return map.withEvents(events);
      }
    }
    throw new IllegalArgumentException("地图事件不存在: " + event.id());
  }

  public ManualCraftovMap setEnabled(ManualCraftovMap map, String eventId, boolean enabled) {
    Preconditions.checkNotNull(map, "map");
    MapEventDefinition current = require(map, eventId);
    if (current.enabled() == enabled) return map;
    if (!enabled) {
      requireNoExtractionReference(map, eventId);
      return replaceWithoutValidation(map, withEnabled(current, false));
    }
    MapEventDefinition updated = withEnabled(current, true);
    validateEditable(map, updated, eventId);
    return replaceWithoutValidation(map, updated);
  }

  public ManualCraftovMap remove(ManualCraftovMap map, String eventId) {
    Preconditions.checkNotNull(map, "map");
    require(map, eventId);
    requireNoExtractionReference(map, eventId);
    return map.withEvents(map.events().stream()
        .filter(event -> !event.id().equals(eventId)).toList());
  }

  /** Enabled AI zones not claimed by another enabled event in the current draft. */
  public List<String> availableAiZoneIds(ManualCraftovMap map,
      @Nullable String excludedEventId) {
    Preconditions.checkNotNull(map, "map");
    return map.aiZones().stream().filter(AiZoneDefinition::enabled)
        .map(AiZoneDefinition::id)
        .filter(id -> !claimedAiZone(map, id, excludedEventId))
        .toList();
  }

  /** Enabled Loot points not claimed by another enabled event in the current draft. */
  public List<String> availableLootPointIds(ManualCraftovMap map,
      @Nullable String excludedEventId) {
    Preconditions.checkNotNull(map, "map");
    return map.lootPoints().stream().filter(LootPointDefinition::enabled)
        .map(LootPointDefinition::id)
        .filter(id -> !claimedLootPoint(map, id, excludedEventId))
        .toList();
  }

  public MapEventDefinition require(ManualCraftovMap map, String eventId) {
    Preconditions.checkNotNull(map, "map");
    Preconditions.checkNotNull(eventId, "eventId");
    MapEventDefinition event = find(map, eventId);
    if (event == null) throw new IllegalArgumentException("地图事件不存在: " + eventId);
    return event;
  }

  private void validateEditable(ManualCraftovMap map, MapEventDefinition event,
      @Nullable String excludedEventId) {
    Preconditions.checkArgument(event.type() == MapEventType.HIGH_VALUE_TARGET,
        "当前只支持 HIGH_VALUE_TARGET 地图事件");
    Preconditions.checkArgument(!event.displayName().isBlank(), "地图事件显示名不能为空");
    Preconditions.checkArgument(event.displayName().length() <= 64
        && event.displayName().chars().noneMatch(Character::isISOControl),
        "地图事件显示名不能超过 64 个字符或包含控制字符");
    Preconditions.checkArgument(event.startDelaySeconds() >= 0
        && event.startDelaySeconds() <= 3_600, "地图事件延迟必须为 0..3600 秒");
    Preconditions.checkArgument(Double.isFinite(event.healthMultiplier())
        && event.healthMultiplier() > 0 && event.healthMultiplier() <= 20,
        "地图事件生命倍率必须是有限值且在 (0, 20] 内");
    Preconditions.checkArgument(Double.isFinite(event.damageMultiplier())
        && event.damageMultiplier() > 0 && event.damageMultiplier() <= 8,
        "地图事件伤害倍率必须是有限值且在 (0, 8] 内");
    Preconditions.checkArgument(event.protectionSeconds() >= 0
        && event.protectionSeconds() <= 300, "地图事件保护时间必须为 0..300 秒");
    Preconditions.checkArgument(availableAiZoneIds(map, excludedEventId)
        .contains(event.aiZoneId()), "AI 区域不存在、未启用或已被其他启用事件占用: "
            + event.aiZoneId());
    Preconditions.checkArgument(availableLootPointIds(map, excludedEventId)
        .contains(event.cacheLootPointId()), "Loot 点不存在、未启用或已被其他启用事件占用: "
            + event.cacheLootPointId());
  }

  private static ManualCraftovMap replaceWithoutValidation(ManualCraftovMap map,
      MapEventDefinition replacement) {
    List<MapEventDefinition> events = new ArrayList<>(map.events());
    for (int index = 0; index < events.size(); index++) {
      if (events.get(index).id().equals(replacement.id())) {
        events.set(index, replacement);
        return map.withEvents(events);
      }
    }
    throw new IllegalArgumentException("地图事件不存在: " + replacement.id());
  }

  private static MapEventDefinition withEnabled(MapEventDefinition event, boolean enabled) {
    return new MapEventDefinition(event.id(), event.displayName(), event.type(), event.aiZoneId(),
        event.cacheLootPointId(), event.startDelaySeconds(), event.healthMultiplier(),
        event.damageMultiplier(), event.protectionSeconds(), enabled);
  }

  private static void requireNoExtractionReference(ManualCraftovMap map, String eventId) {
    List<String> references = map.extractions().stream().filter(extraction ->
            extraction.conditions().stream()
                .filter(ExtractionRuleDefinition.EventCompleted.class::isInstance)
                .map(ExtractionRuleDefinition.EventCompleted.class::cast)
                .anyMatch(rule -> rule.eventId().equals(eventId)))
        .map(ExtractionDefinition::id).toList();
    Preconditions.checkArgument(references.isEmpty(),
        "撤离点仍引用地图事件 " + eventId + ": " + String.join(", ", references)
            + "；请先移除 EVENT_COMPLETED 条件");
  }

  private static boolean claimedAiZone(ManualCraftovMap map, String aiZoneId,
      @Nullable String excludedEventId) {
    return map.events().stream().filter(MapEventDefinition::enabled)
        .filter(event -> excludedEventId == null || !event.id().equals(excludedEventId))
        .anyMatch(event -> event.aiZoneId().equals(aiZoneId));
  }

  private static boolean claimedLootPoint(ManualCraftovMap map, String lootPointId,
      @Nullable String excludedEventId) {
    return map.events().stream().filter(MapEventDefinition::enabled)
        .filter(event -> excludedEventId == null || !event.id().equals(excludedEventId))
        .anyMatch(event -> event.cacheLootPointId().equals(lootPointId));
  }

  private static MapEventDefinition find(ManualCraftovMap map, String eventId) {
    return map.events().stream().filter(event -> event.id().equals(eventId))
        .findFirst().orElse(null);
  }
}
