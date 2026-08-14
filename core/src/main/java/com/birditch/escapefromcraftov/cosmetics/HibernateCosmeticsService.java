package com.birditch.escapefromcraftov.cosmetics;

import com.birditch.escapefromcraftov.api.CraftovException;
import com.birditch.escapefromcraftov.api.cosmetics.Cosmetic;
import com.birditch.escapefromcraftov.api.cosmetics.CosmeticId;
import com.birditch.escapefromcraftov.api.cosmetics.CosmeticsService;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.database.Database;
import com.birditch.escapefromcraftov.core.database.entity.PlayerCosmeticEntity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/// Hibernate ORM equipped-cosmetic service with an online-player read cache.
public final class HibernateCosmeticsService implements CosmeticsService {

  private final Database database;
  private final ExecutorService ioExecutor;
  private final List<Cosmetic> catalog;
  private final Map<CosmeticId, Cosmetic> catalogById;
  private final ConcurrentHashMap<UUID, Cosmetic> equipped = new ConcurrentHashMap<>();

  public HibernateCosmeticsService(
      Database database, ExecutorService ioExecutor, List<Cosmetic> catalog) {
    this.database = Preconditions.checkNotNull(database, "database");
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
    return CompletableFuture.supplyAsync(() -> loadNow(playerId), ioExecutor)
        .thenAccept(cosmetic -> {
          if (cosmetic == null) {
            equipped.remove(playerId);
          } else {
            equipped.put(playerId, cosmetic);
          }
        });
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
    return CompletableFuture.runAsync(() -> equipNow(playerId, id), ioExecutor)
        .thenRun(() -> equipped.put(playerId, cosmetic));
  }

  @Override
  public CompletableFuture<Void> unequip(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return CompletableFuture.runAsync(() -> unequipNow(playerId), ioExecutor)
        .thenRun(() -> equipped.remove(playerId));
  }

  @Override
  public void unload(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    equipped.remove(playerId);
  }

  private Cosmetic loadNow(UUID playerId) {
    try {
      String cosmeticId = database.transaction(session -> {
        PlayerCosmeticEntity entity = session.find(PlayerCosmeticEntity.class, playerId);
        return entity == null ? null : entity.cosmeticId();
      });
      if (cosmeticId == null) return null;
      try {
        return catalogById.get(CosmeticId.of(cosmeticId));
      } catch (IllegalArgumentException _) {
        // Unknown or retired cosmetic IDs are treated as unequipped.
        return null;
      }
    } catch (RuntimeException e) {
      throw new CraftovException("Failed to load cosmetic for " + playerId, e);
    }
  }

  private void equipNow(UUID playerId, CosmeticId id) {
    try {
      database.transaction(session -> {
        PlayerCosmeticEntity entity = session.find(PlayerCosmeticEntity.class, playerId);
        Instant now = Instant.now();
        if (entity == null) {
          session.persist(new PlayerCosmeticEntity(playerId, id.value(), now));
        } else {
          entity.update(id.value(), now);
        }
        return null;
      });
    } catch (RuntimeException e) {
      throw new CraftovException("Failed to equip cosmetic for " + playerId, e);
    }
  }

  private void unequipNow(UUID playerId) {
    try {
      database.transaction(session -> {
        PlayerCosmeticEntity entity = session.find(PlayerCosmeticEntity.class, playerId);
        if (entity != null) {
          session.remove(entity);
        }
        return null;
      });
    } catch (RuntimeException e) {
      throw new CraftovException("Failed to unequip cosmetic for " + playerId, e);
    }
  }
}
