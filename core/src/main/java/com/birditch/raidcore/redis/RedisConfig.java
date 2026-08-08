package com.birditch.raidcore.redis;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.config.ConfigurationException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// Connection settings for the shared Redis fabric. Construction never validates
/// value ranges; call [#validate] after loading. A `null` or blank password means
/// "no authentication".
public record RedisConfig(
    String host,
    int port,
    @Nullable String password,
    int database,
    PoolSettings pool) {

  public RedisConfig {
    Preconditions.checkNotNull(host, "host");
    Preconditions.checkNotNull(pool, "pool");
  }

  /// Checks connection settings and aggregates every problem into a single
  /// exception so operators can fix all of them at once.
  ///
  /// @throws ConfigurationException listing every invalid setting
  public void validate() {
    List<String> problems = new ArrayList<>();
    if (host.isBlank()) {
      problems.add("redis.host must not be blank");
    }
    if (port < 1 || port > 65535) {
      problems.add("redis.port must be between 1 and 65535");
    }
    if (database < 0) {
      problems.add("redis.database must not be negative");
    }
    if (pool.maxTotal() <= 0) {
      problems.add("redis.pool.max-total must be greater than 0");
    }
    if (pool.maxIdle() <= 0) {
      problems.add("redis.pool.max-idle must be greater than 0");
    }
    if (pool.minIdle() <= 0) {
      problems.add("redis.pool.min-idle must be greater than 0");
    }
    if (!problems.isEmpty()) {
      throw new ConfigurationException(
          "Invalid redis configuration:\n" + String.join("\n", problems));
    }
  }

  public record PoolSettings(int maxTotal, int maxIdle, int minIdle) {}
}
