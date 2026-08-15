package com.birditch.escapefromcraftov.game.plugin.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import com.birditch.escapefromcraftov.map.BlockPosition;
import java.util.List;
import com.birditch.escapefromcraftov.map.EditorTool;
import org.bukkit.Color;
import org.junit.jupiter.api.Test;

class EditorVisualizationServiceTest {

  @Test
  void assignsStableColorsToEveryEditorTool() {
    assertEquals(Color.ORANGE, EditorVisualizationService.color(EditorTool.MAP));
    assertEquals(Color.LIME, EditorVisualizationService.color(EditorTool.SPAWN));
    assertEquals(Color.YELLOW, EditorVisualizationService.color(EditorTool.LOOT));
    assertEquals(Color.YELLOW, EditorVisualizationService.color(EditorTool.LOOT_ZONE));
    assertEquals(Color.RED, EditorVisualizationService.color(EditorTool.AI_ZONE));
    assertEquals(Color.AQUA, EditorVisualizationService.color(EditorTool.EXTRACTION));
    assertEquals(Color.PURPLE, EditorVisualizationService.color(EditorTool.POI));
  }

  @Test
  void patrolPreviewShowsOrderWithinOneWholeFrameBudget() {
    List<BlockPosition> route = java.util.stream.IntStream.range(
        0, CraftovAiZone.MAXIMUM_PATROL_WAYPOINTS)
        .mapToObj(index -> new BlockPosition(index * 10, 64, index * 5)).toList();

    List<PatrolRoutePreviewPlanner.Sample> samples = PatrolRoutePreviewPlanner.plan(route);

    assertTrue(samples.size() <= PatrolRoutePreviewPlanner.MAXIMUM_SAMPLES_PER_FRAME);
    assertEquals(PatrolRoutePreviewPlanner.MAXIMUM_SAMPLES_PER_FRAME, samples.size());
    assertEquals(PatrolRoutePreviewPlanner.Kind.START, samples.getFirst().kind());
    assertTrue(samples.stream().anyMatch(sample ->
        sample.kind() == PatrolRoutePreviewPlanner.Kind.ARROW));
    assertTrue(samples.stream().anyMatch(sample ->
        sample.kind() == PatrolRoutePreviewPlanner.Kind.PATH));
  }
}
