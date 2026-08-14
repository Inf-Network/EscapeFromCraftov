package com.birditch.escapefromcraftov.lobby;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.config.ConfigurationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Lobby plugin settings loaded from `config.yml`. Construction never validates
/// value ranges; call [#validate] after loading.
public record LobbyConfig(
    int queueTickSeconds,
    int queueMinimumPlayers,
    int reservationTtlSeconds,
    int assignmentTtlSeconds,
    boolean protectLobby,
    boolean scoreboardEnabled,
    int scoreboardRefreshTicks,
    int inventoryRefreshSeconds,
    CraftovMapId defaultMap,
    Set<CraftovMapId> enabledMaps) {

  public LobbyConfig {
    Preconditions.checkNotNull(defaultMap, "defaultMap");
    Preconditions.checkNotNull(enabledMaps, "enabledMaps");
    enabledMaps = Collections.unmodifiableSet(new LinkedHashSet<>(enabledMaps));
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
    if (queueMinimumPlayers <= 0) {
      problems.add("queue.minimum-players must be greater than 0");
    }
    if (reservationTtlSeconds <= 0) {
      problems.add("queue.reservation-ttl-seconds must be greater than 0");
    }
    if (assignmentTtlSeconds <= 0) {
      problems.add("queue.assignment-ttl-seconds must be greater than 0");
    }
    if (scoreboardRefreshTicks < 1 || scoreboardRefreshTicks > 100) {
      problems.add("scoreboard.refresh-ticks must be 1..100");
    }
    if (inventoryRefreshSeconds < 1 || inventoryRefreshSeconds > 300) {
      problems.add("scoreboard.inventory-refresh-seconds must be 1..300");
    }
    if (enabledMaps.isEmpty()) {
      problems.add("lobby.enabled-maps must contain at least one map");
    } else if (!enabledMaps.contains(defaultMap)) {
      problems.add("lobby.default-map must be included in lobby.enabled-maps");
    }
    if (!problems.isEmpty()) {
      throw new ConfigurationException(
          "Invalid lobby configuration:\n" + String.join("\n", problems));
    }
  }
}
