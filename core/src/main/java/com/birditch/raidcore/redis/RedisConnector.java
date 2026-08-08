package com.birditch.raidcore.redis;

import com.birditch.raidcore.common.check.Preconditions;
import org.jspecify.annotations.Nullable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

/// Owns the [JedisPool] shared by every Redis-backed service. Jedis types never
/// escape this package: [#resource] is package-private by design.
///
/// Every call on a borrowed [Jedis] blocks — use resources only from the io
/// executor or a virtual thread, never from the server main thread.
public final class RedisConnector {

  private final JedisPool pool;

  public RedisConnector(RedisConfig config) {
    Preconditions.checkNotNull(config, "config");
    JedisPoolConfig poolConfig = new JedisPoolConfig();
    poolConfig.setMaxTotal(config.pool().maxTotal());
    poolConfig.setMaxIdle(config.pool().maxIdle());
    poolConfig.setMinIdle(config.pool().minIdle());
    this.pool = new JedisPool(poolConfig, config.host(), config.port(),
        Protocol.DEFAULT_TIMEOUT, authOrNull(config.password()), config.database());
  }

  /// Borrows a pooled connection; the caller must close it (try-with-resources).
  Jedis resource() {
    return pool.getResource();
  }

  public void close() {
    pool.close();
  }

  private static @Nullable String authOrNull(@Nullable String password) {
    return password == null || password.isBlank() ? null : password;
  }
}
