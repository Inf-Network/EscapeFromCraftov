package com.birditch.escapefromcraftov.game.plugin;

import com.birditch.escapefromcraftov.core.config.ConfigurationException;
import java.util.ArrayList;
import java.util.List;

/// Player-facing combat, inventory and Sidebar settings.
public record GameplaySettings(
    boolean friendlyFire,
    int attributionSeconds,
    boolean scoreboardEnabled,
    int scoreboardRefreshTicks,
    int lootContainerSize,
    int extractionExperience,
    int deathExperience,
    int killExperience) {

  public void validate() {
    List<String> problems = new ArrayList<>();
    if (attributionSeconds < 1 || attributionSeconds > 120) {
      problems.add("combat.attribution-seconds must be 1..120");
    }
    if (scoreboardRefreshTicks < 1 || scoreboardRefreshTicks > 100) {
      problems.add("scoreboard.refresh-ticks must be 1..100");
    }
    if (lootContainerSize < 9 || lootContainerSize > 54 || lootContainerSize % 9 != 0) {
      problems.add("inventory.loot-container-size must be a multiple of 9 from 9..54");
    }
    if (extractionExperience < 1 || deathExperience < 1 || killExperience < 1) {
      problems.add("progression experience rewards must all be positive");
    }
    if (!problems.isEmpty()) {
      throw new ConfigurationException("Invalid gameplay configuration:\n"
          + String.join("\n", problems));
    }
  }
}
