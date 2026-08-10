package com.birditch.raidcore.lobby;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LobbyDataRecoveryBoundaryTest {

  @Test
  void orphanedRaidItemsArePurgedBeforeTheLobbyGateOpens() throws Exception {
    String source = Files.readString(projectRoot().resolve(
        "lobby/src/main/java/com/birditch/raidcore/lobby/listener/LobbyConnectionListener.java"));

    int purge = source.indexOf("purgeOrphanedRaidItems(playerId)");
    int ready = source.indexOf("dataGate().markReady(playerId)");
    assertTrue(purge >= 0, "lobby join must purge abandoned raid items");
    assertTrue(ready > purge, "data gate must open only after abandoned raid item recovery");
    assertTrue(source.contains("recovered)"), "purge future must participate in the load barrier");
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
