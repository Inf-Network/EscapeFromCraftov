package com.birditch.escapefromcraftov.game.plugin.deathcrate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
}
