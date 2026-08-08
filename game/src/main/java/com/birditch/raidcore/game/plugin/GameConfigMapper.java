package com.birditch.raidcore.game.plugin;

import com.birditch.raidcore.api.network.ServerId;
import com.birditch.raidcore.common.check.Preconditions;
import org.bukkit.configuration.file.FileConfiguration;

/// Maps the `server`/`game`/`network` sections of `config.yml` onto
/// [GameServerConfig]. Values are read leniently with the bundled defaults as
/// fallback; range validation happens via [GameServerConfig#validate].
public final class GameConfigMapper {

  private GameConfigMapper() {}

  public static GameServerConfig read(FileConfiguration yaml) {
    Preconditions.checkNotNull(yaml, "yaml");
    return new GameServerConfig(
        ServerId.of(stringOr(yaml, "server.id", "game-01")),
        stringOr(yaml, "server.proxy-name", "raid-01"),
        yaml.getInt("game.min-ready-instances", 1),
        stringOr(yaml, "game.map-template-dir", "maps"),
        yaml.getBoolean("network.proxy-enabled", true),
        stringOr(yaml, "network.lobby-server-name", "lobby"),
        yaml.getInt("network.snapshot-interval-seconds", 2));
  }

  private static String stringOr(FileConfiguration yaml, String path, String fallback) {
    String value = yaml.getString(path);
    return value != null ? value : fallback;
  }
}
