package com.birditch.raidcore.core.database;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.config.ConfigurationException;
import java.util.Locale;

/// Storage backend selection. `MEMORY` needs no external services and is the
/// development default; `POSTGRESQL` requires a reachable database.
public enum DatabaseType {
  MEMORY,
  POSTGRESQL;

  /// Parses a configuration value case-insensitively.
  ///
  /// @throws ConfigurationException if `raw` matches no known type
  public static DatabaseType parse(String raw) {
    Preconditions.checkNotNull(raw, "raw");
    return switch (raw.toLowerCase(Locale.ROOT)) {
      case "memory" -> MEMORY;
      case "postgresql" -> POSTGRESQL;
      default -> throw new ConfigurationException("Unknown database type: " + raw);
    };
  }
}
