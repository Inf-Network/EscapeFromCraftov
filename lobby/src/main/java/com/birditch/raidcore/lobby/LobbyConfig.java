package com.birditch.raidcore.lobby;

import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.config.ConfigurationException;
import java.util.ArrayList;
import java.util.List;

/// Lobby plugin settings loaded from `config.yml`. Construction never validates
/// value ranges; call [#validate] after loading.
public record LobbyConfig(
    int queueTickSeconds,
    int reservationTtlSeconds,
    int assignmentTtlSeconds,
    boolean protectLobby,
    RaidMapId defaultMap) {

  public LobbyConfig {
    Preconditions.checkNotNull(defaultMap, "defaultMap");
  }

  /// Checks the loaded settings and aggregates every problem into a single
  /// exception so operators can fix all of them at once.
  ///
  /// @throws ConfigurationException listing every invalid setting
  public void validate() {
    List<String> problems = new ArrayList<>();
    if (queueTickSeconds <= 0) {
      problems.add("queue.tick-seconds must be greater than 0");
    }
    if (reservationTtlSeconds <= 0) {
      problems.add("queue.reservation-ttl-seconds must be greater than 0");
    }
    if (assignmentTtlSeconds <= 0) {
      problems.add("queue.assignment-ttl-seconds must be greater than 0");
    }
    if (!problems.isEmpty()) {
      throw new ConfigurationException(
          "Invalid lobby configuration:\n" + String.join("\n", problems));
    }
  }
}
