package com.birditch.escapefromcraftov.game.plugin.editor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AiPatrolMutationBoundaryTest {

  @Test
  void objectMenuPropertyChangesNeverDropAnExistingPatrolRoute() throws IOException {
    String java = Files.readString(Path.of(
        "src/main/java/com/birditch/escapefromcraftov/game/plugin/editor/EditorObjectMenuController.java"));
    int start = java.indexOf("case AiZoneDefinition value -> switch (action)");
    int end = java.indexOf("case ExtractionDefinition value", start);
    String aiProperties = java.substring(start, end);

    assertTrue(occurrences(aiProperties, "value.patrolWaypoints()") >= 5);
    int enabledStart = java.indexOf("case AiZoneDefinition value -> new AiZoneDefinition",
        end);
    int enabledEnd = java.indexOf("case ExtractionDefinition value", enabledStart);
    assertTrue(java.substring(enabledStart, enabledEnd).contains("value.patrolWaypoints()"));
  }

  private static int occurrences(String value, String needle) {
    int result = 0;
    for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0; offset += needle.length()) {
      result++;
    }
    return result;
  }
}
