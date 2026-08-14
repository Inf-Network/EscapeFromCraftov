package com.birditch.escapefromcraftov.game.plugin;

import com.birditch.escapefromcraftov.api.network.ServerId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.bukkit.CoreConfigMapper;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import org.bukkit.configuration.file.FileConfiguration;

/// Maps the `server`/`game`/`network` sections of `config.yml` onto
/// [GameServerConfig]. Values are read leniently with the bundled defaults as
/// fallback; range validation happens via [GameServerConfig#validate].
public final class GameConfigMapper {

  private GameConfigMapper() {}

  public static GameServerConfig read(FileConfiguration yaml) {
    Preconditions.checkNotNull(yaml, "yaml");
    return new GameServerConfig(
        ServerId.of(CoreConfigMapper.stringOr(yaml, "server.id", "game-01")),
        CoreConfigMapper.stringOr(yaml, "server.proxy-name", "craftov-01"),
        CraftovMapId.of(CoreConfigMapper.stringOr(yaml, "game.default-map", "debug_zone")),
        yaml.getInt("game.minimum-players", 2),
        yaml.getInt("game.start-countdown-seconds", 15),
        yaml.getInt("game.waiting-arrival-timeout-seconds",
            GameServerConfig.DEFAULT_WAITING_ARRIVAL_TIMEOUT_SECONDS),
        yaml.getInt("game.min-ready-instances", 1),
        CoreConfigMapper.stringOr(yaml, "game.map-template-dir", "maps"),
        yaml.getBoolean("game.allow-flat-fallback", false),
        CoreConfigMapper.stringOr(yaml, "network.lobby-server-name", "lobby"),
        yaml.getInt("network.snapshot-interval-seconds", 2));
  }

  public static GameplaySettings readGameplay(FileConfiguration yaml) {
    Preconditions.checkNotNull(yaml, "yaml");
    return new GameplaySettings(
        yaml.getBoolean("combat.friendly-fire", false),
        yaml.getInt("combat.attribution-seconds", 15),
        yaml.getBoolean("scoreboard.enabled", true),
        yaml.getInt("scoreboard.refresh-ticks", 10),
        yaml.getInt("inventory.loot-container-size", 27),
        yaml.getInt("progression.extraction-experience", 250),
        yaml.getInt("progression.death-experience", 25),
        yaml.getInt("progression.kill-experience", 100));
  }
}
