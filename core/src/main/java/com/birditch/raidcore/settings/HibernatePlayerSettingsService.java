package com.birditch.raidcore.settings;

import com.birditch.raidcore.api.RaidException;
import com.birditch.raidcore.api.settings.PlayerSettingsService;
import com.birditch.raidcore.api.settings.SettingKey;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.database.Database;
import com.birditch.raidcore.core.database.entity.PlayerSettingEntity;
import com.birditch.raidcore.core.database.entity.PlayerSettingId;
import java.time.Instant;
import java.util.EnumMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/// Hibernate ORM player settings with an online-player read cache.
public final class HibernatePlayerSettingsService implements PlayerSettingsService {

  private final Database database;
  private final ExecutorService ioExecutor;
  private final ConcurrentHashMap<UUID, EnumMap<SettingKey, Boolean>> cache =
      new ConcurrentHashMap<>();

  public HibernatePlayerSettingsService(Database database, ExecutorService ioExecutor) {
    this.database = Preconditions.checkNotNull(database, "database");
    this.ioExecutor = Preconditions.checkNotNull(ioExecutor, "ioExecutor");
  }

  @Override
  public CompletableFuture<Void> load(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return CompletableFuture.supplyAsync(() -> loadNow(playerId), ioExecutor)
        .thenAccept(settings -> cache.put(playerId, settings));
  }

  @Override
  public boolean get(UUID playerId, SettingKey key) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(key, "key");
    EnumMap<SettingKey, Boolean> settings = cache.get(playerId);
    return settings == null ? key.defaultValue() : settings.getOrDefault(key, key.defaultValue());
  }

  @Override
  public CompletableFuture<Void> set(UUID playerId, SettingKey key, boolean value) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(key, "key");
    return CompletableFuture.runAsync(() -> saveNow(playerId, key, value), ioExecutor)
        .thenRun(() -> cache.compute(playerId, (_, existing) -> {
          EnumMap<SettingKey, Boolean> updated =
              existing == null ? new EnumMap<>(SettingKey.class) : new EnumMap<>(existing);
          updated.put(key, value);
          return updated;
        }));
  }

  @Override
  public void unload(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    cache.remove(playerId);
  }

  private EnumMap<SettingKey, Boolean> loadNow(UUID playerId) {
    try {
      return database.transaction(session -> {
        EnumMap<SettingKey, Boolean> settings = new EnumMap<>(SettingKey.class);
        for (PlayerSettingEntity entity : session.createSelectionQuery(
                "from PlayerSettingEntity where id.playerId = :playerId",
                PlayerSettingEntity.class)
            .setParameter("playerId", playerId)
            .getResultList()) {
          try {
            settings.put(SettingKey.valueOf(entity.id().settingKey()), entity.enabled());
          } catch (IllegalArgumentException _) {
            // Retired setting keys remain harmless historical data.
          }
        }
        return settings;
      });
    } catch (RuntimeException e) {
      throw new RaidException("Failed to load settings for " + playerId, e);
    }
  }

  private void saveNow(UUID playerId, SettingKey key, boolean value) {
    try {
      database.transaction(session -> {
        PlayerSettingId id = new PlayerSettingId(playerId, key.name());
        PlayerSettingEntity entity = session.find(PlayerSettingEntity.class, id);
        Instant now = Instant.now();
        if (entity == null) {
          session.persist(new PlayerSettingEntity(id, value, now));
        } else {
          entity.update(value, now);
        }
        return null;
      });
    } catch (RuntimeException e) {
      throw new RaidException("Failed to save setting " + key + " for " + playerId, e);
    }
  }
}
