package com.birditch.raidcore.lobby;

import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.bukkit.CoreConfigMapper;
import com.birditch.raidcore.common.check.Preconditions;
import org.bukkit.configuration.file.FileConfiguration;

/// Maps `config.yml` onto [LobbyConfig]. Values are read leniently with the
/// bundled defaults as fallback; range validation happens via
/// [LobbyConfig#validate].
public final class LobbyConfigMapper {

  private LobbyConfigMapper() {}

  public static LobbyConfig read(FileConfiguration yaml) {
    Preconditions.checkNotNull(yaml, "yaml");
    return new LobbyConfig(
        yaml.getInt("queue.tick-seconds", 1),
        yaml.getInt("queue.reservation-ttl-seconds", 30),
        yaml.getInt("queue.assignment-ttl-seconds", 30),
        yaml.getBoolean("lobby.protect", true),
        RaidMapId.of(CoreConfigMapper.stringOr(yaml, "lobby.default-map", "debug_zone")));
  }
}
