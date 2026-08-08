package com.birditch.raidcore.game.plugin;

import com.birditch.raidcore.api.network.ServerId;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.config.ConfigurationException;
import java.util.ArrayList;
import java.util.List;

/// Network identity and self-provisioning settings of this game server.
/// Construction never validates value ranges; call [#validate] after loading.
/// The instance cap lives in
/// [com.birditch.raidcore.core.config.RaidCoreConfig.GameSettings#maxInstances].
public record GameServerConfig(
    ServerId serverId,
    String proxyName,
    int minReadyInstances,
    String mapTemplateDir,
    boolean proxyEnabled,
    String lobbyServerName,
    int snapshotIntervalSeconds) {

  public GameServerConfig {
    Preconditions.checkNotNull(serverId, "serverId");
    Preconditions.checkNotNull(proxyName, "proxyName");
    Preconditions.checkNotNull(mapTemplateDir, "mapTemplateDir");
    Preconditions.checkNotNull(lobbyServerName, "lobbyServerName");
  }

  /// Checks all settings and aggregates every problem.
  ///
  /// @throws ConfigurationException listing every invalid setting
  public void validate() {
    List<String> problems = new ArrayList<>();
    if (minReadyInstances < 0) {
      problems.add("game.min-ready-instances must not be negative");
    }
    if (snapshotIntervalSeconds < 1) {
      problems.add("network.snapshot-interval-seconds must be at least 1");
    }
    if (proxyEnabled && lobbyServerName.isBlank()) {
      problems.add("network.lobby-server-name must not be blank when network.proxy-enabled");
    }
    if (!problems.isEmpty()) {
      throw new ConfigurationException(
          "Invalid server configuration:\n" + String.join("\n", problems));
    }
  }
}
