package com.birditch.escapefromcraftov.game.plugin.deathcrate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DeathCrateMenuBoundaryTest {

  @Test
  void menuExposesOnlyValidatedTakeAndPageActions() throws IOException {
    String yaml;
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
        "trmenu/escapefromcraftov_death_crate.yml")) {
      assertNotNull(stream);
      yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertTrue(yaml.contains("Hide-Player-Inventory: true"));
    for (int slot = 0; slot < 45; slot++) {
      assertTrue(yaml.contains("source:CRAFTOVCOREDEATH:slot-" + slot));
      assertTrue(yaml.contains("command: craftov ui death-crate take " + slot));
    }
    assertTrue(yaml.contains("command: craftov ui death-crate page previous"));
    assertTrue(yaml.contains("command: craftov ui death-crate page next"));
    assertFalse(yaml.contains("death-crate store"));
    assertFalse(yaml.contains("Bukkit.createInventory"));
  }

  @Test
  void ordinaryLootSkipsDeathMarkersAndOnlyAnUnvetoedMonitorCommitOpensThem()
      throws Exception {
    Path root = projectRoot();
    String death = Files.readString(root.resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/deathcrate/DeathCrateListener.java"));
    String marker = method(death, "public void onInteractMarker", "/** Opens only");
    String commit = method(death, "public void onInteractCommit", "public void take");
    assertFalse(marker.contains("event.setCancelled(true)"),
        "LOWEST must leave LOW/HIGH protection listeners an uncancelled event");
    assertTrue(commit.indexOf("Event.Result.DENY") < commit.indexOf("event.setCancelled(true)"));
    assertTrue(commit.indexOf("event.setCancelled(true)") < commit.indexOf("openMenu("));

    String loot = Files.readString(root.resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/loot/CraftovLootListener.java"));
    String interact = method(loot, "public void onInteract", "public void onQuit");
    assertTrue(interact.contains("deathCrateMarker.test(targetBlock)"),
        "normal death-crate clicks must never also start authored Loot search");
    String application = Files.readString(root.resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/GameApplication.java"));
    assertTrue(application.contains("current != null && current.isMarker(block)"));
  }

  private static String method(String source, String startText, String endText) {
    int start = source.indexOf(startText);
    int end = source.indexOf(endText, start + startText.length());
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
