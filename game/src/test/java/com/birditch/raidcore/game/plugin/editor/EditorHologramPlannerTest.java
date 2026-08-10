package com.birditch.raidcore.game.plugin.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.game.plugin.integration.EditorHologramView;
import com.birditch.raidcore.map.BlockPosition;
import com.birditch.raidcore.map.CuboidBounds;
import com.birditch.raidcore.map.LootPointDefinition;
import com.birditch.raidcore.map.ManualRaidMap;
import com.birditch.raidcore.map.MapObject;
import com.birditch.raidcore.map.MapValidationIssue;
import com.birditch.raidcore.map.MapValidationSeverity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EditorHologramPlannerTest {

  @Test
  void keepsSelectedObjectAndNearestErrorsWithinTheFrameLimit() {
    List<LootPointDefinition> loot = new ArrayList<>();
    Map<String, List<MapValidationIssue>> validation = new LinkedHashMap<>();
    for (int index = 0; index < 20; index++) {
      String id = "loot_" + index;
      loot.add(loot(id, index));
      validation.put(id, List.of(error(id)));
    }
    ManualRaidMap map = map(loot);
    List<MapObject> nearestFirst = loot.stream().map(MapObject.class::cast).toList();

    var labels = EditorHologramPlanner.plan(map, nearestFirst, validation, "loot_19", 5);

    assertEquals(5, labels.size());
    assertEquals("selected_loot_19", labels.getFirst().key());
    assertTrue(labels.getFirst().selected());
    assertEquals(List.of("error_loot_0", "error_loot_1", "error_loot_2", "error_loot_3"),
        labels.subList(1, labels.size()).stream().map(
            EditorHologramPlanner.PlannedLabel::key).toList());
    assertTrue(labels.subList(1, labels.size()).stream().noneMatch(
        EditorHologramPlanner.PlannedLabel::selected));
  }

  @Test
  void errorsFillTheConfiguredLimitBeforeNormalNearbyObjects() {
    List<LootPointDefinition> loot = new ArrayList<>();
    Map<String, List<MapValidationIssue>> validation = new LinkedHashMap<>();
    for (int index = 0; index < 8; index++) {
      String id = "loot_" + index;
      loot.add(loot(id, index));
      if (index % 2 == 0) validation.put(id, List.of(error(id)));
    }

    var labels = EditorHologramPlanner.plan(map(loot),
        loot.stream().map(MapObject.class::cast).toList(), validation, null, 6);

    assertEquals(6, labels.size());
    assertEquals(List.of("error_loot_0", "error_loot_2", "error_loot_4", "error_loot_6",
        "nearby_loot_1", "nearby_loot_3"), labels.stream()
        .map(EditorHologramPlanner.PlannedLabel::key).toList());
    assertEquals(List.of(EditorHologramView.Style.ERROR, EditorHologramView.Style.ERROR,
        EditorHologramView.Style.ERROR, EditorHologramView.Style.ERROR,
        EditorHologramView.Style.NEARBY, EditorHologramView.Style.NEARBY), labels.stream()
        .map(EditorHologramPlanner.PlannedLabel::style).toList());
  }

  @Test
  void includesEveryNearbyObjectWhenTheHologramBudgetAllowsIt() {
    List<LootPointDefinition> loot = List.of(loot("loot_0", 0), loot("loot_1", 1),
        loot("loot_2", 2));

    var labels = EditorHologramPlanner.plan(map(loot),
        loot.stream().map(MapObject.class::cast).toList(), Map.of(), null, 8);

    assertEquals(List.of("nearby_loot_0", "nearby_loot_1", "nearby_loot_2"), labels.stream()
        .map(EditorHologramPlanner.PlannedLabel::key).toList());
    assertTrue(labels.stream().allMatch(label ->
        label.style() == EditorHologramView.Style.NEARBY));
  }

  private static ManualRaidMap map(List<LootPointDefinition> loot) {
    return new ManualRaidMap(ManualRaidMap.SCHEMA_VERSION, "test", "Test", "world",
        new CuboidBounds(-100, 0, -100, 100, 100, 100), 1, false, Map.of(), List.of(),
        loot, List.of(), List.of(), List.of(), List.of());
  }

  private static LootPointDefinition loot(String id, int x) {
    return new LootPointDefinition(id, new BlockPosition(x, 64, 0), "virtual", "default",
        1, true, true);
  }

  private static MapValidationIssue error(String id) {
    return new MapValidationIssue(MapValidationSeverity.ERROR, "loot_invalid", id,
        "物资点不可用");
  }
}
