package com.birditch.escapefromcraftov.game.plugin.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class AiWakeBoundaryTest {

  @Test
  void broadWakeDoesNotAddLeashOrLineOfSightCost() throws IOException {
    String source = Files.readString(javaSource());
    String wake = between(source, "boolean participantNearby", "boolean actorInsideLeash");

    assertTrue(wake.contains("AiTacticsPolicy.canWake"));
    assertFalse(wake.contains("insideLeash"));
    assertFalse(wake.contains("hasLineOfSight"));
  }

  @Test
  void acquisitionChecksCheapLeashAndRadiusBeforePaperLineOfSight() throws IOException {
    String source = Files.readString(javaSource());
    String nearest = between(source,
        "private static @Nullable Player nearestVisible", "private static boolean insideLeash");

    int leashGate = nearest.indexOf("if (!insideLeash(zone, candidate)) continue");
    int cheapGate = nearest.indexOf("AiTacticsPolicy.canDetect(");
    int lineOfSight = nearest.indexOf("mob.hasLineOfSight(player)");
    assertTrue(leashGate >= 0);
    assertTrue(cheapGate > leashGate);
    assertTrue(cheapGate >= 0);
    assertTrue(lineOfSight > cheapGate,
        "Paper LOS must run only after leash, distance and nearest-candidate gates");
    assertFalse(nearest.substring(cheapGate, lineOfSight).contains("hasLineOfSight"));
  }

  private static String between(String source, String start, String end) {
    int first = source.indexOf(start);
    int last = source.indexOf(end, first + start.length());
    assertTrue(first >= 0 && last > first);
    return source.substring(first, last);
  }

  private static Path javaSource() {
    Path current = Path.of("").toAbsolutePath();
    Path local = current.resolve(
        "src/main/java/com/birditch/escapefromcraftov/game/plugin/ai/CraftovAiRuntime.java");
    return Files.exists(local) ? local : current.resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/ai/CraftovAiRuntime.java");
  }
}
