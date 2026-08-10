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
        stringOr(yaml, "database.schema", "public"),
        stringOr(yaml, "database.ssl-mode", "prefer"),
        stringOr(yaml, "database.application-name", "RaidCore"),
        yaml.getBoolean("database.tcp-keepalive", true),
        yaml.getBoolean("database.rewrite-batched-inserts", true),
        yaml.getInt("database.connect-timeout-seconds", 10),
        yaml.getInt("database.socket-timeout-seconds", 30),
        new DatabaseConfig.PoolSettings(
            yaml.getInt("database.pool.maximum-pool-size", 10),
            yaml.getInt("database.pool.minimum-idle", 2),
            yaml.getLong("database.pool.connection-timeout", 10_000L),
            yaml.getLong("database.pool.validation-timeout", 5_000L),
            yaml.getLong("database.pool.idle-timeout", 600_000L),
            yaml.getLong("database.pool.max-lifetime", 1_800_000L),
            yaml.getLong("database.pool.keepalive-time", 120_000L),
            yaml.getLong("database.pool.leak-detection-threshold", 0L),
            yaml.getLong("database.pool.initialization-fail-timeout", 1L),
            stringOr(yaml, "database.pool.transaction-isolation", "TRANSACTION_READ_COMMITTED"),
            stringOr(yaml, "database.pool.connection-init-sql", ""),
            yaml.getBoolean("database.pool.isolate-internal-queries", true),
            yaml.getBoolean("database.pool.register-mbeans", false)),
        new DatabaseConfig.OrmSettings(
            stringOr(yaml, "database.orm.schema-action", "update"),
            yaml.getInt("database.orm.statement-batch-size", 25),
            yaml.getInt("database.orm.default-batch-fetch-size", 16),
            yaml.getBoolean("database.orm.show-sql", false),
            yaml.getBoolean("database.orm.format-sql", false),
            yaml.getBoolean("database.orm.generate-statistics", false)));
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

  /// Shared by every config mapper (core, game, lobby) — the single string-or-default helper.
  public static String stringOr(FileConfiguration yaml, String path, String fallback) {
    String value = yaml.getString(path);
    return value != null ? value : fallback;
  }
}
