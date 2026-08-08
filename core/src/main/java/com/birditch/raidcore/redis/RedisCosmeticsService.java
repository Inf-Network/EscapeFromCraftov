package com.birditch.raidcore.redis;

import com.birditch.raidcore.api.cosmetics.Cosmetic;
import com.birditch.raidcore.api.cosmetics.CosmeticId;
import com.birditch.raidcore.api.cosmetics.CosmeticsService;
import com.birditch.raidcore.api.network.NetworkKeys;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;
import redis.clients.jedis.Jedis;

/// Equipped cosmetics persisted as one Redis string per player (value = cosmetic
/// id). [#equipped] only ever reads the local cache; [#equip]/[#unequip] update
/// the cache immediately and persist asynchronously. A stored id that no longer
/// exists in the catalog is treated as "nothing equipped".
public final class RedisCosmeticsService implements CosmeticsService {

  private final RedisConnector redis;
  private final ExecutorService ioExecutor;
  private final List<Cosmetic> catalog;
  private final Map<CosmeticId, Cosmetic> catalogById;
  private final ConcurrentHashMap<UUID, Cosmetic> equipped = new ConcurrentHashMap<>();

  public RedisCosmeticsService(
      RedisConnector redis, ExecutorService ioExecutor, List<Cosmetic> catalog) {
    this.redis = Preconditions.checkNotNull(redis, "redis");
    this.ioExecutor = Preconditions.checkNotNull(ioExecutor, "ioExecutor");
    Preconditions.checkNotNull(catalog, "catalog");
    this.catalog = List.copyOf(catalog);
    Map<CosmeticId, Cosmetic> byId = new LinkedHashMap<>();
    for (Cosmetic cosmetic : this.catalog) {
      Preconditions.checkArgument(byId.putIfAbsent(cosmetic.id(), cosmetic) == null,
          "Duplicate cosmetic id: " + cosmetic.id().value());
    }
    this.catalogById = Map.copyOf(byId);
  }

  @Override
  public List<Cosmetic> catalog() {
    return catalog;
  }

  @Override
  public CompletableFuture<Void> load(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return CompletableFuture.runAsync(() -> {
      String storedId;
      try (Jedis jedis = redis.resource()) {
        storedId = jedis.get(NetworkKeys.cosmeticsKey(playerId));
      }
      Cosmetic cosmetic = storedId == null ? null : findByRawId(storedId);
      if (cosmetic == null) {
        equipped.remove(playerId);
      } else {
        equipped.put(playerId, cosmetic);
      }
    }, ioExecutor);
  }

  @Override
  public Optional<Cosmetic> equipped(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return Optional.ofNullable(equipped.get(playerId));
  }

  @Override
  public CompletableFuture<Void> equip(UUID playerId, CosmeticId id) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(id, "id");
    Cosmetic cosmetic = catalogById.get(id);
    if (cosmetic == null) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Unknown cosmetic: " + id.value()));
    }
    equipped.put(playerId, cosmetic);
    return CompletableFuture.runAsync(() -> {
      try (Jedis jedis = redis.resource()) {
        jedis.set(NetworkKeys.cosmeticsKey(playerId), cosmetic.id().value());
      }
    }, ioExecutor);
  }

  @Override
  public CompletableFuture<Void> unequip(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    equipped.remove(playerId);
    return CompletableFuture.runAsync(() -> {
      try (Jedis jedis = redis.resource()) {
        jedis.del(NetworkKeys.cosmeticsKey(playerId));
      }
    }, ioExecutor);
  }

  @Override
  public void unload(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    equipped.remove(playerId);
  }

  /// Compares raw stored text against catalog ids instead of constructing a
  /// [CosmeticId], so malformed stored values degrade to "nothing equipped"
  /// rather than failing the load future.
  private @Nullable Cosmetic findByRawId(String rawId) {
    for (Cosmetic cosmetic : catalog) {
      if (cosmetic.id().value().equals(rawId)) {
        return cosmetic;
      }
    }
    return null;
  }
}
