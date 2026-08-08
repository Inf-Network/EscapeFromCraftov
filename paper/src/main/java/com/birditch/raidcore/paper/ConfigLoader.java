package com.birditch.raidcore.paper;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.config.RaidCoreConfig;
import com.birditch.raidcore.core.database.DatabaseConfig;
import com.birditch.raidcore.core.database.DatabaseType;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/// Loads plugin YAML resources, copying the bundled defaults into the data
/// folder on first run. Values are read leniently with the bundled defaults as
/// fallback; range validation happens via the config records' `validate()`.
public final class ConfigLoader {

  private final Plugin plugin;

  public ConfigLoader(Plugin plugin) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
  }

  public RaidCoreConfig loadConfig() {
    YamlConfiguration yaml = load("config.yml");
    return new RaidCoreConfig(
        yaml.getBoolean("debug", false),
        new RaidCoreConfig.GameSettings(
            yaml.getInt("game.max-players", 12),
            yaml.getInt("game.team-size", 3),
            yaml.getInt("game.duration-seconds", 1800)),
        new RaidCoreConfig.ExtractionSettings(
            yaml.getInt("extraction.default-duration-seconds", 10)));
  }

  public DatabaseConfig loadDatabaseConfig() {
    YamlConfiguration yaml = load("database.yml");
    return new DatabaseConfig(
        DatabaseType.parse(stringOr(yaml, "database.type", "memory")),
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
  public Map<String, String> loadMessages() {
    YamlConfiguration yaml = load("messages.yml");
    Map<String, String> messages = new LinkedHashMap<>();
    for (String key : yaml.getKeys(true)) {
      if (yaml.isString(key)) {
        messages.put(key, stringOr(yaml, key, ""));
      }
    }
    return Map.copyOf(messages);
  }

  private YamlConfiguration load(String fileName) {
    File file = new File(plugin.getDataFolder(), fileName);
    if (!file.exists()) {
      plugin.saveResource(fileName, false);
    }
    return YamlConfiguration.loadConfiguration(file);
  }

  private static String stringOr(YamlConfiguration yaml, String path, String fallback) {
    String value = yaml.getString(path);
    return value != null ? value : fallback;
  }
}
