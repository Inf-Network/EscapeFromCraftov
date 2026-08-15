package com.birditch.escapefromcraftov.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManualMapEventEditorTest {

  private final ManualMapEventEditor editor = new ManualMapEventEditor();

  @Test
  void eventMutationsParticipateInTheExistingDraftUndoRedoHistory() {
    EditorSession session = new EditorSession(UUID.randomUUID(), mapWithTwoResources()
        .withEvents(List.of()));
    MapEventDefinition created = event("target_a", "ai_zone_a", "loot_a", true);

    assertTrue(session.apply(map -> editor.add(map, created)));
    assertTrue(session.dirty());
    assertEquals("target_a", session.draft().events().getFirst().id());

    MapEventDefinition renamed = copy(created, "高价值目标", 30, 5, 2, 45, true,
        "ai_zone_a", "loot_a");
    assertTrue(session.apply(map -> editor.replace(map, renamed)));
    assertEquals("高价值目标", session.draft().events().getFirst().displayName());

    assertTrue(session.apply(map -> editor.remove(map, "target_a")));
    assertTrue(session.draft().events().isEmpty());
    assertTrue(session.undo());
    assertEquals("高价值目标", session.draft().events().getFirst().displayName());
    assertTrue(session.undo());
    assertEquals("target_a", session.draft().events().getFirst().displayName());
    assertTrue(session.redo());
    assertEquals("高价值目标", session.draft().events().getFirst().displayName());
  }

  @Test
  void createAndReferenceChangesRecomputeEnabledUnclaimedResources() {
    ManualCraftovMap map = mapWithTwoResources().withEvents(List.of(
        event("claimed", "ai_zone_a", "loot_a", true)));

    assertEquals(List.of("ai_zone_b"), editor.availableAiZoneIds(map, null));
    assertEquals(List.of("loot_b"), editor.availableLootPointIds(map, null));
    assertThrows(IllegalArgumentException.class,
        () -> editor.add(map, event("target_b", "ai_zone_a", "loot_b", true)));
    assertThrows(IllegalArgumentException.class,
        () -> editor.add(map, event("target_b", "ai_zone_b", "loot_a", true)));

    ManualCraftovMap added = editor.add(map,
        event("target_b", "ai_zone_b", "loot_b", true));
    assertTrue(editor.availableAiZoneIds(added, null).isEmpty());
    assertTrue(editor.availableLootPointIds(added, null).isEmpty());
  }

  @Test
  void referenceReplacementAndReenableUseProspectiveAtomicOccupancy() {
    MapEventDefinition first = event("first", "ai_zone_a", "loot_a", true);
    MapEventDefinition second = event("second", "ai_zone_b", "loot_b", false);
    ManualCraftovMap map = mapWithTwoResources().withEvents(List.of(first, second));

    MapEventDefinition moved = copy(first, first.displayName(), first.startDelaySeconds(),
        first.healthMultiplier(), first.damageMultiplier(), first.protectionSeconds(), true,
        "ai_zone_b", "loot_b");
    ManualCraftovMap replaced = editor.replace(map, moved);
    assertEquals(List.of("ai_zone_a"), editor.availableAiZoneIds(replaced, null));
    assertEquals(List.of("loot_a"), editor.availableLootPointIds(replaced, null));
    assertThrows(IllegalArgumentException.class,
        () -> editor.setEnabled(replaced, "second", true));

    ManualCraftovMap disabled = editor.setEnabled(replaced, "first", false);
    assertEquals(List.of("ai_zone_a", "ai_zone_b"), editor.availableAiZoneIds(disabled, null));
    assertTrue(editor.setEnabled(disabled, "second", true).events().get(1).enabled());
  }

  @Test
  void rejectsDisabledReferencesIdConflictsAndUnsafeTuning() {
    ManualCraftovMap source = mapWithTwoResources().withEvents(List.of());
    AiZoneDefinition disabledAi = new AiZoneDefinition("disabled_ai",
        new CuboidBounds(1, 1, 1, 5, 5, 5), "scav", 1, 1, 60, 1, "scav", false);
    LootPointDefinition disabledLoot = new LootPointDefinition("disabled_loot",
        new BlockPosition(1, 1, 1), "virtual", "default", 1, true, false);
    ManualCraftovMap map = copyResources(source,
        concat(source.lootPoints(), disabledLoot), concat(source.aiZones(), disabledAi));

    assertThrows(IllegalArgumentException.class,
        () -> editor.add(map, event("spawn_a", "ai_zone_a", "loot_a", true)));
    assertThrows(IllegalArgumentException.class,
        () -> editor.add(map, event("bad_ref", "disabled_ai", "disabled_loot", true)));
    assertThrows(IllegalArgumentException.class, () -> editor.add(map,
        copy(event("bad_name", "ai_zone_a", "loot_a", true), "x".repeat(65), 0,
            1, 1, 0, true, "ai_zone_a", "loot_a")));
    assertThrows(IllegalArgumentException.class, () -> editor.add(map,
        copy(event("control_name", "ai_zone_a", "loot_a", true), "Boss\nName", 0,
            1, 1, 0, true, "ai_zone_a", "loot_a")));
    assertThrows(IllegalArgumentException.class, () -> editor.add(map,
        copy(event("bad_delay", "ai_zone_a", "loot_a", true), "bad_delay", -1,
            1, 1, 0, true, "ai_zone_a", "loot_a")));
    assertThrows(IllegalArgumentException.class, () -> editor.add(map,
        copy(event("bad_health", "ai_zone_a", "loot_a", true), "bad_health", 0,
            Double.NaN, 1, 0, true, "ai_zone_a", "loot_a")));
    assertThrows(IllegalArgumentException.class, () -> editor.add(map,
        copy(event("bad_damage", "ai_zone_a", "loot_a", true), "bad_damage", 0,
            1, 9, 0, true, "ai_zone_a", "loot_a")));
    assertThrows(IllegalArgumentException.class, () -> editor.add(map,
        copy(event("bad_protection", "ai_zone_a", "loot_a", true), "bad_protection", 0,
            1, 1, 301, true, "ai_zone_a", "loot_a")));
  }

  @Test
  void everyAuthoredExtractionReferenceBlocksDisableAndDeleteAndNamesTheExit() {
    ManualCraftovMap source = mapWithTwoResources();
    ExtractionDefinition original = source.extractions().getFirst();
    ExtractionDefinition referring = new ExtractionDefinition(original.id(),
        original.displayName(), original.bounds(), original.extractionType(),
        original.durationSeconds(), original.conditionValue(), true,
        List.of(new ExtractionRuleDefinition.EventCompleted("factory_overseer")),
        original.routeScope());
    ManualCraftovMap referenced = copyExtractions(source,
        replaceFirst(source.extractions(), referring));

    IllegalArgumentException enabledFailure = assertThrows(IllegalArgumentException.class,
        () -> editor.setEnabled(referenced, "factory_overseer", false));
    assertTrue(enabledFailure.getMessage().contains(original.id()));
    assertThrows(IllegalArgumentException.class,
        () -> editor.remove(referenced, "factory_overseer"));
    MapEventDefinition event = source.events().getFirst();
    MapEventDefinition disabledViaReplace = copy(event, event.displayName(),
        event.startDelaySeconds(), event.healthMultiplier(), event.damageMultiplier(),
        event.protectionSeconds(), false, event.aiZoneId(), event.cacheLootPointId());
    assertThrows(IllegalArgumentException.class,
        () -> editor.replace(referenced, disabledViaReplace));

    ExtractionDefinition disabled = new ExtractionDefinition(referring.id(),
        referring.displayName(), referring.bounds(), referring.extractionType(),
        referring.durationSeconds(), referring.conditionValue(), false, referring.conditions(),
        referring.routeScope());
    ManualCraftovMap disabledExit = copyExtractions(source,
        replaceFirst(source.extractions(), disabled));
    IllegalArgumentException disabledFailure = assertThrows(IllegalArgumentException.class,
        () -> editor.remove(disabledExit, "factory_overseer"));
    assertTrue(disabledFailure.getMessage().contains(original.id()));
  }

  private static ManualCraftovMap mapWithTwoResources() {
    ManualCraftovMap source = ManualMapFixtures.validMap();
    LootPointDefinition loot = new LootPointDefinition("loot_b",
        new BlockPosition(350, 64, 250), "virtual", "default", 2, true, true);
    AiZoneDefinition ai = new AiZoneDefinition("ai_zone_b",
        new CuboidBounds(310, 50, 210, 390, 80, 290), "scav", 2, 4, 60, 1,
        "scav", true);
    return copyResources(source, concat(source.lootPoints(), loot), concat(source.aiZones(), ai));
  }

  private static MapEventDefinition event(String id, String aiZone, String loot, boolean enabled) {
    return new MapEventDefinition(id, id, MapEventType.HIGH_VALUE_TARGET, aiZone, loot,
        15, 4, 1.5, 30, enabled);
  }

  private static MapEventDefinition copy(MapEventDefinition source, String name, int delay,
      double health, double damage, int protection, boolean enabled, String aiZone, String loot) {
    return new MapEventDefinition(source.id(), name, source.type(), aiZone, loot, delay, health,
        damage, protection, enabled);
  }

  private static <T> List<T> concat(List<T> values, T value) {
    java.util.ArrayList<T> result = new java.util.ArrayList<>(values);
    result.add(value);
    return List.copyOf(result);
  }

  private static <T> List<T> replaceFirst(List<T> values, T value) {
    java.util.ArrayList<T> result = new java.util.ArrayList<>(values);
    result.set(0, value);
    return List.copyOf(result);
  }

  private static ManualCraftovMap copyResources(ManualCraftovMap source,
      List<LootPointDefinition> lootPoints, List<AiZoneDefinition> aiZones) {
    return new ManualCraftovMap(source.schemaVersion(), source.mapId(), source.displayName(),
        source.worldName(), source.bounds(), source.revision(), source.enabled(), source.metadata(),
        source.spawns(), lootPoints, source.lootZones(), aiZones, source.extractions(),
        source.pois(), source.actionTiers(), source.events());
  }

  private static ManualCraftovMap copyExtractions(ManualCraftovMap source,
      List<ExtractionDefinition> extractions) {
    return new ManualCraftovMap(source.schemaVersion(), source.mapId(), source.displayName(),
        source.worldName(), source.bounds(), source.revision(), source.enabled(), source.metadata(),
        source.spawns(), source.lootPoints(), source.lootZones(), source.aiZones(), extractions,
        source.pois(), source.actionTiers(), source.events());
  }
}
