package com.birditch.escapefromcraftov.game.plugin.instance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExtractionSettlementBoundaryTest {

  @Test
  void inventorySnapshotAndInteractionLockStartAtTheLockedEvent() throws Exception {
    String source = Files.readString(projectRoot().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/instance/InstanceWorldBridge.java"));
    String locked = method(source, "onPlayerExtractionLocked", "onPlayerExtractionAuthorizationFailed");
    String ready = method(source, "onPlayerExtractionReady", "onSettlingMove");

    assertTrue(locked.contains("scanCarriedItems(player)"));
    assertTrue(locked.contains("pendingExtractions.put"));
    assertTrue(locked.contains("freeze(event.playerId())"));
    assertTrue(ready.indexOf("pendingExtractions.get(key)")
        < ready.indexOf("scanCarriedItems(player)"),
        "ready must consume the locked snapshot before its defensive recovery path");
  }

  private static String method(String source, String startName, String nextName) {
    int start = source.indexOf("void " + startName);
    int end = source.indexOf("void " + nextName, start + 1);
    if (start < 0 || end < 0) throw new IllegalStateException("method boundary not found");
    return source.substring(start, end);
  }

  private static Path projectRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
      current = current.getParent();
    }
    if (current == null) throw new IllegalStateException("project root not found");
    return current;
  }
}
