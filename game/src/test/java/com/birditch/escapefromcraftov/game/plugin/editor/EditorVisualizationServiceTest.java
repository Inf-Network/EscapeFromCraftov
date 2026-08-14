package com.birditch.escapefromcraftov.game.plugin.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
