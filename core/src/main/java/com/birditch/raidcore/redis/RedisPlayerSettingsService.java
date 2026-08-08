package com.birditch.raidcore.redis;

import com.birditch.raidcore.api.network.NetworkKeys;
import com.birditch.raidcore.api.settings.PlayerSettingsService;
import com.birditch.raidcore.api.settings.SettingKey;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import redis.clients.jedis.Jedis;

/// Player settings persisted in a Redis hash per player (field = setting name,
/// value `"1"`/`"0"`). [#get] only ever reads the local cache: it falls back to
/// [SettingKey#defaultValue] until [#load] completes, and [#set] updates the
/// cache immediately (copy-on-write) while persisting asynchronously.
public final class RedisPlayerSettingsService implements PlayerSettingsService {

  private final RedisConnector redis;
  private final ExecutorService ioExecutor;
  private final ConcurrentHashMap<UUID, EnumMap<SettingKey, Boolean>> cache =
      new ConcurrentHashMap<>();

  public RedisPlayerSettingsService(RedisConnector redis, ExecutorService ioExecutor) {
    this.redis = Preconditions.checkNotNull(redis, "redis");
    this.ioExecutor = Preconditions.checkNotNull(ioExecutor, "ioExecutor");
  }

  @Override
  public CompletableFuture<Void> load(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return CompletableFuture.runAsync(() -> {
      Map<String, String> fields;
      try (Jedis jedis = redis.resource()) {
        fields = jedis.hgetAll(NetworkKeys.settingsKey(playerId));
      }
      EnumMap<SettingKey, Boolean> settings = new EnumMap<>(SettingKey.class);
      for (SettingKey key : SettingKey.values()) {
        String raw = fields.get(key.name());
        if (raw != null) {
          settings.put(key, "1".equals(raw));
        }
      }
      cache.put(playerId, settings);
    }, ioExecutor);
  }

  @Override
  public boolean get(UUID playerId, SettingKey key) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(key, "key");
    EnumMap<SettingKey, Boolean> settings = cache.get(playerId);
    if (settings == null) {
      return key.defaultValue();
    }
    Boolean value = settings.get(key);
    return value != null ? value : key.defaultValue();
  }

  @Override
  public CompletableFuture<Void> set(UUID playerId, SettingKey key, boolean value) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(key, "key");
    cache.compute(playerId, (_, existing) -> {
      EnumMap<SettingKey, Boolean> updated =
          existing == null ? new EnumMap<>(SettingKey.class) : new EnumMap<>(existing);
      updated.put(key, value);
      return updated;
    });
    return CompletableFuture.runAsync(() -> {
      try (Jedis jedis = redis.resource()) {
        jedis.hset(NetworkKeys.settingsKey(playerId), key.name(), value ? "1" : "0");
      }
    }, ioExecutor);
  }

  @Override
  public void unload(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    cache.remove(playerId);
  }
}
