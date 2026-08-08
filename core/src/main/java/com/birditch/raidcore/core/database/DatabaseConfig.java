package com.birditch.raidcore.core.database;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.config.ConfigurationException;
import java.util.ArrayList;
import java.util.List;

/// Connection settings for the configured [DatabaseType]. Construction never
/// validates value ranges; call [#validate] after loading.
public record DatabaseConfig(
    DatabaseType type,
    String host,
    int port,
    String database,
    String username,
    String password,
    PoolSettings pool) {

  public DatabaseConfig {
    Preconditions.checkNotNull(type, "type");
    Preconditions.checkNotNull(host, "host");
    Preconditions.checkNotNull(database, "database");
    Preconditions.checkNotNull(username, "username");
    Preconditions.checkNotNull(password, "password");
    Preconditions.checkNotNull(pool, "pool");
  }

  /// Checks connection settings and aggregates every problem. `MEMORY` needs no
  /// external services, so validation only applies to `POSTGRESQL`.
  ///
  /// @throws ConfigurationException listing every invalid setting
  public void validate() {
    if (type != DatabaseType.POSTGRESQL) {
      return;
    }
    List<String> problems = new ArrayList<>();
    if (host.isBlank()) {
      problems.add("database.host must not be blank");
    }
    if (port < 1 || port > 65535) {
      problems.add("database.port must be between 1 and 65535");
    }
    if (database.isBlank()) {
      problems.add("database.database must not be blank");
    }
    if (username.isBlank()) {
      problems.add("database.username must not be blank");
    }
    if (pool.maximumPoolSize() <= 0) {
      problems.add("database.pool.maximum-pool-size must be greater than 0");
    }
    if (pool.minimumIdle() <= 0) {
      problems.add("database.pool.minimum-idle must be greater than 0");
    }
    if (!problems.isEmpty()) {
      throw new ConfigurationException(
          "Invalid database configuration:\n" + String.join("\n", problems));
    }
  }

  public record PoolSettings(int maximumPoolSize, int minimumIdle, long connectionTimeoutMs) {}
}
