package com.birditch.escapefromcraftov.game.plugin;

import com.birditch.escapefromcraftov.api.network.ServerId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.config.ConfigurationException;
import java.util.ArrayList;
import java.util.List;

/// Network identity and self-provisioning settings of this game server.
/// Construction never validates value ranges; call [#validate] after loading.
/// The instance cap lives in
/// [com.birditch.escapefromcraftov.core.config.EscapeFromCraftovConfig.GameSettings#maxInstances].
public record GameServerConfig(
    ServerId serverId,
    String proxyName,
    CraftovMapId defaultMap,
    int minimumPlayers,
    int startCountdownSeconds,
    int minReadyInstances,
    String mapTemplateDir,
    boolean allowFlatFallback,
    String lobbyServerName,
    int snapshotIntervalSeconds) {

  public GameServerConfig {
    Preconditions.checkNotNull(serverId, "serverId");
    Preconditions.checkNotNull(proxyName, "proxyName");
    Preconditions.checkNotNull(defaultMap, "defaultMap");
    Preconditions.checkNotNull(mapTemplateDir, "mapTemplateDir");
    Preconditions.checkNotNull(lobbyServerName, "lobbyServerName");
  }

  /// Checks all settings and aggregates every problem.
  ///
  /// @throws ConfigurationException listing every invalid setting
  public void validate(int maxInstances, int maxPlayers) {
    List<String> problems = new ArrayList<>();
    if (proxyName.isBlank()) {
      problems.add("server.proxy-name must not be blank");
    }
    if (minReadyInstances < 0) {
      problems.add("game.min-ready-instances must not be negative");
    } else if (minReadyInstances > maxInstances) {
      problems.add("game.min-ready-instances must not exceed game.max-instances");
    }
    if (minimumPlayers < 1 || minimumPlayers > maxPlayers) {
      problems.add("game.minimum-players must be 1..game.max-players");
    }
    if (startCountdownSeconds < 3 || startCountdownSeconds > 300) {
      problems.add("game.start-countdown-seconds must be 3..300");
    }
    if (mapTemplateDir.isBlank()) {
      problems.add("game.map-template-dir must not be blank");
    }
    if (snapshotIntervalSeconds < 1) {
      problems.add("network.snapshot-interval-seconds must be at least 1");
    }
    if (lobbyServerName.isBlank()) {
      problems.add("network.lobby-server-name must not be blank");
    }
    if (!problems.isEmpty()) {
      throw new ConfigurationException(
          "Invalid server configuration:\n" + String.join("\n", problems));
    }
  }
}
