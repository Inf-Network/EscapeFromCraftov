package com.birditch.raidcore.redis;

import com.birditch.raidcore.common.check.Preconditions;
import org.jspecify.annotations.Nullable;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.RedisClient;

/// Owns the modern pooled [RedisClient] shared by every Redis-backed service.
/// Jedis types never escape this package: [#client] is package-private by design.
///
/// Every call can block while borrowing or using a connection — invoke it only
/// from the IO executor or a virtual thread, never from the Paper main thread.
public final class RedisConnector {

  private final RedisClient client;

  public RedisConnector(RedisConfig config) {
    Preconditions.checkNotNull(config, "config");
    ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
    poolConfig.setMaxTotal(config.pool().maxTotal());
    poolConfig.setMaxIdle(config.pool().maxIdle());
    poolConfig.setMinIdle(config.pool().minIdle());
    var clientConfig = DefaultJedisClientConfig.builder()
        .connectionTimeoutMillis(Protocol.DEFAULT_TIMEOUT)
        .socketTimeoutMillis(Protocol.DEFAULT_TIMEOUT)
        .password(authOrNull(config.password()))
        .database(config.database())
        .build();
    this.client = RedisClient.builder()
        .hostAndPort(config.host(), config.port())
        .clientConfig(clientConfig)
        .poolConfig(poolConfig)
        .build();
  }

  /// Shared thread-safe client; individual operations borrow pooled connections.
  RedisClient client() {
    return client;
  }

  public void close() {
    client.close();
  }

  private static @Nullable String authOrNull(@Nullable String password) {
    return password == null || password.isBlank() ? null : password;
  }
}
