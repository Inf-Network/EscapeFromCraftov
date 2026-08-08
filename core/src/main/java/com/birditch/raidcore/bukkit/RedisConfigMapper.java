package com.birditch.raidcore.bukkit;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.redis.RedisConfig;
import org.bukkit.configuration.file.FileConfiguration;

/// Maps `redis.yml` onto [RedisConfig]. A blank password means
/// "no authentication".
public final class RedisConfigMapper {

  private RedisConfigMapper() {}

  public static RedisConfig read(FileConfiguration yaml) {
    Preconditions.checkNotNull(yaml, "yaml");
    return new RedisConfig(
        CoreConfigMapper.stringOr(yaml, "redis.host", "localhost"),
        yaml.getInt("redis.port", 6379),
        yaml.getString("redis.password"),
        yaml.getInt("redis.database", 0),
        new RedisConfig.PoolSettings(
            yaml.getInt("redis.pool.max-total", 8),
            yaml.getInt("redis.pool.max-idle", 8),
            yaml.getInt("redis.pool.min-idle", 1)));
  }
}
