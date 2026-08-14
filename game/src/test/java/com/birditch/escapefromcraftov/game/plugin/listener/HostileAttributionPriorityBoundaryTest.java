package com.birditch.escapefromcraftov.game.plugin.listener;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HostileAttributionPriorityBoundaryTest {

  private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();

  @Test
  void frozenPlayersCancelBeforeCombatAttributionAndDownedRunsAfterIt() throws IOException {
    String pending = read(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/network/PendingPlayerGuard.java");
    String bridge = read(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/instance/InstanceWorldBridge.java");
    String combat = read(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/listener/GameCombatListener.java");
    String downed = read(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/downed/DownedPlayerListener.java");

    assertHandler(pending, "onDamage(EntityDamageEvent event)",
        "EventPriority.LOW, ignoreCancelled = false");
    assertHandler(pending, "onAttack(EntityDamageByEntityEvent event)",
        "EventPriority.LOW, ignoreCancelled = false");
    assertHandler(bridge, "onSettlingDamage(EntityDamageByEntityEvent event)",
        "EventPriority.LOW, ignoreCancelled = false");
    assertHandler(bridge, "onSettlingIncomingDamage(EntityDamageEvent event)",
        "EventPriority.LOW, ignoreCancelled = false");
    assertHandler(combat, "onDamage(EntityDamageEvent event)",
        "EventPriority.HIGH, ignoreCancelled = true");
    assertHandler(downed, "onDamage(EntityDamageEvent event)",
        "EventPriority.HIGHEST, ignoreCancelled = true");
  }

  private static void assertHandler(String source, String signature, String annotation) {
    int method = source.indexOf(signature);
    int handler = source.lastIndexOf("@EventHandler", method);
    assertTrue(method >= 0 && handler >= 0 && method - handler < 200,
        "missing handler for " + signature);
    assertTrue(source.substring(handler, method).contains(annotation),
        signature + " must use " + annotation);
  }

  private static String read(String path) throws IOException {
    return Files.readString(REPOSITORY.resolve(path));
  }
}
