package com.birditch.raidcore.bukkit;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.config.RaidCoreConfig;
import com.birditch.raidcore.core.database.DatabaseConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.file.FileConfiguration;

/// Maps loaded YAML onto the core config records. Values are read leniently
/// with the bundled defaults as fallback; range validation happens via the
/// records' `validate()`.
public final class CoreConfigMapper {

  private CoreConfigMapper() {}

  public static RaidCoreConfig readCoreConfig(FileConfiguration yaml) {
    Preconditions.checkNotNull(yaml, "yaml");
    return new RaidCoreConfig(
        yaml.getBoolean("debug", false),
        new RaidCoreConfig.GameSettings(
            yaml.getInt("game.max-players", 12),
            yaml.getInt("game.team-size", 3),
            yaml.getInt("game.duration-seconds", 1800),
            yaml.getInt("game.max-instances", 4)),
        new RaidCoreConfig.ExtractionSettings(
            yaml.getInt("extraction.default-duration-seconds", 10)));
  }

  public static DatabaseConfig readDatabaseConfig(FileConfiguration yaml) {
    Preconditions.checkNotNull(yaml, "yaml");
    return new DatabaseConfig(
        stringOr(yaml, "database.host", "localhost"),
        yaml.getInt("database.port", 5432),
        stringOr(yaml, "database.database", "raidcore"),
        stringOr(yaml, "database.username", "raidcore"),
        stringOr(yaml, "database.password", "change-me"),
        new DatabaseConfig.PoolSettings(
            yaml.getInt("database.pool.maximum-pool-size", 10),
            yaml.getInt("database.pool.minimum-idle", 2),
            yaml.getLong("database.pool.connection-timeout", 10_000L)));
  }

  /// Flattens every string leaf of `messages.yml` into dotted keys.
  public static Map<String, String> readMessages(FileConfiguration yaml) {
    Preconditions.checkNotNull(yaml, "yaml");
    Map<String, String> messages = new LinkedHashMap<>();
    for (String key : yaml.getKeys(true)) {
      if (yaml.isString(key)) {
        messages.put(key, stringOr(yaml, key, ""));
      }
    }
    return Map.copyOf(messages);
  }

  static String stringOr(FileConfiguration yaml, String path, String fallback) {
    String value = yaml.getString(path);
    return value != null ? value : fallback;
  }
}
