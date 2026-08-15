package com.birditch.escapefromcraftov.game.plugin.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CraftovAiPatrolBoundaryTest {

  @Test
  void authoredPatrolReusesLoadedChunkAndGlobalPathBudgetBoundaries() throws IOException {
    String java = Files.readString(Path.of(
        "src/main/java/com/birditch/escapefromcraftov/game/plugin/ai/CraftovAiRuntime.java"));
    int start = java.indexOf("private void patrolAuthoredRoute");
    int end = java.indexOf("private MoveAttempt moveTo", start);
    String patrol = java.substring(start, end);

    assertTrue(patrol.contains("moveTo(handle, mob, destination, 0.8, now, paths)"));
    assertTrue(patrol.contains("AiPatrolPolicy.Progress.ARRIVED"));
    assertFalse(patrol.contains("getChunkAt"));
    assertFalse(patrol.contains("loadChunk"));
    assertTrue(java.contains("new PathBudget(MAXIMUM_PATHS_PER_TICK)"));
    assertTrue(java.contains("Math.min(MAXIMUM_AGENT_DECISIONS_PER_TICK, snapshot.size())"));
  }

  @Test
  void returnStillUsesSafeHomeAndKeepsTheAuthoredCursor() throws IOException {
    String java = Files.readString(Path.of(
        "src/main/java/com/birditch/escapefromcraftov/game/plugin/ai/CraftovAiRuntime.java"));
    int behavior = java.indexOf("private void applyBehavior");
    int patrol = java.indexOf("private void patrol(", behavior);
    String switchBody = java.substring(behavior, patrol);

    assertTrue(switchBody.contains("moveTo(handle, mob, agent.home, 1.1, now, paths)"));
    assertFalse(switchBody.contains("patrolWaypointIndex"));
  }

  @Test
  void enteringAlertStopsThePreviousPatrolPathBeforeTargeting() throws IOException {
    String java = Files.readString(Path.of(
        "src/main/java/com/birditch/escapefromcraftov/game/plugin/ai/CraftovAiRuntime.java"));
    int start = java.indexOf("private void alert(");
    int end = java.indexOf("private void retreatAfterBoundaryHit", start);
    String alert = java.substring(start, end);

    assertTrue(alert.contains("if (changed) mob.getPathfinder().stopPathfinding()"));
    assertTrue(alert.indexOf("stopPathfinding()") < alert.indexOf("propagateAlert("));
  }
}
