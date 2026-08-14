package com.birditch.escapefromcraftov.lobby;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.bukkit.CoreConfigMapper;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

/// Maps `config.yml` onto [LobbyConfig]. Values are read leniently with the
/// bundled defaults as fallback; range validation happens via
/// [LobbyConfig#validate].
public final class LobbyConfigMapper {

  private LobbyConfigMapper() {}

  public static LobbyConfig read(FileConfiguration yaml) {
    Preconditions.checkNotNull(yaml, "yaml");
    CraftovMapId defaultMap =
        CraftovMapId.of(CoreConfigMapper.stringOr(yaml, "lobby.default-map", "debug_zone"));
    List<String> configuredMaps = yaml.getStringList("lobby.enabled-maps");
    Set<CraftovMapId> enabledMaps = new LinkedHashSet<>();
    if (configuredMaps.isEmpty()) {
      enabledMaps.add(defaultMap);
    } else {
      configuredMaps.stream().map(CraftovMapId::of).forEach(enabledMaps::add);
    }
    return new LobbyConfig(
        yaml.getInt("queue.tick-seconds", 1),
        yaml.getInt("queue.minimum-players", 2),
        yaml.getInt("queue.reservation-ttl-seconds", 30),
        yaml.getInt("queue.assignment-ttl-seconds", 30),
        yaml.getBoolean("lobby.protect", true),
        yaml.getBoolean("scoreboard.enabled", true),
        yaml.getInt("scoreboard.refresh-ticks", 20),
        yaml.getInt("scoreboard.inventory-refresh-seconds", 5),
        defaultMap,
        enabledMaps);
  }
}
