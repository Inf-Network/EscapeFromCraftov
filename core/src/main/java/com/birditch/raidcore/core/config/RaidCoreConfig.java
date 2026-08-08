package com.birditch.raidcore.core.config;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.ArrayList;
import java.util.List;

/// Root configuration for the RaidCore runtime. Construction never validates value
/// ranges; call [#validate] after loading so every problem is reported at once.
public record RaidCoreConfig(boolean debug, GameSettings game, ExtractionSettings extraction) {

  public RaidCoreConfig {
    Preconditions.checkNotNull(game, "game");
    Preconditions.checkNotNull(extraction, "extraction");
  }

  /// Checks all settings and aggregates every problem.
  ///
  /// @throws ConfigurationException listing every invalid setting
  public void validate() {
    List<String> problems = new ArrayList<>();
    if (game.maxPlayers() <= 0) {
      problems.add("game.max-players must be greater than 0");
    }
    if (game.teamSize() <= 0) {
      problems.add("game.team-size must be greater than 0");
    }
    if (game.durationSeconds() <= 0) {
      problems.add("game.duration-seconds must be greater than 0");
    }
    if (game.maxInstances() <= 0) {
      problems.add("game.max-instances must be greater than 0");
    }
    if (extraction.defaultDurationSeconds() <= 0) {
      problems.add("extraction.default-duration-seconds must be greater than 0");
    }
    if (!problems.isEmpty()) {
      throw new ConfigurationException("Invalid configuration:\n" + String.join("\n", problems));
    }
  }

  public record GameSettings(
      int maxPlayers, int teamSize, int durationSeconds, int maxInstances) {}

  public record ExtractionSettings(int defaultDurationSeconds) {}
}
