package com.birditch.escapefromcraftov.profile;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.profile.PlayerProfile;
import com.birditch.escapefromcraftov.api.profile.ProfileException;
import com.birditch.escapefromcraftov.api.profile.ProfileRepository;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.database.Database;
import com.birditch.escapefromcraftov.core.database.entity.PlayerProfileEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/// Hibernate ORM profile repository. Domain objects are snapshotted before
/// asynchronous work starts and persistence entities never escape a transaction.
public final class HibernateProfileRepository implements ProfileRepository {

  private final Database database;
  private final ExecutorService ioExecutor;

  public HibernateProfileRepository(Database database, ExecutorService ioExecutor) {
    this.database = Preconditions.checkNotNull(database, "database");
    this.ioExecutor = Preconditions.checkNotNull(ioExecutor, "ioExecutor");
  }

  @Override
  public CompletableFuture<Optional<PlayerProfile>> load(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return CompletableFuture.supplyAsync(() -> loadNow(playerId), ioExecutor);
  }

  @Override
  public CompletableFuture<Void> save(PlayerProfile profile) {
    Preconditions.checkNotNull(profile, "profile");
    ProfileSnapshot snapshot = new ProfileSnapshot(
        profile.playerId(), profile.name(), profile.balance().units(), profile.level(),
        profile.experience(), profile.createdAt());
    return CompletableFuture.runAsync(() -> saveNow(snapshot), ioExecutor);
  }

  @Override
  public CompletableFuture<Void> delete(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return CompletableFuture.runAsync(() -> deleteNow(playerId), ioExecutor);
  }

  private Optional<PlayerProfile> loadNow(UUID playerId) {
    try {
      return database.transaction(session -> Optional.ofNullable(
              session.find(PlayerProfileEntity.class, playerId))
          .map(HibernateProfileRepository::toDomain));
    } catch (RuntimeException e) {
      throw new ProfileException("Failed to load profile " + playerId, e);
    }
  }

  private void saveNow(ProfileSnapshot snapshot) {
    try {
      database.transaction(session -> {
        PlayerProfileEntity entity = session.find(PlayerProfileEntity.class, snapshot.playerId());
        Instant now = Instant.now();
        if (entity == null) {
          session.persist(new PlayerProfileEntity(
              snapshot.playerId(), snapshot.name(), snapshot.balance(), snapshot.level(),
              snapshot.experience(), snapshot.createdAt(), now));
        } else {
          // Economy and progression have their own locked transaction paths.
          // A stale online cache must never overwrite those authoritative fields.
          entity.updateIdentity(snapshot.name(), now);
        }
        return null;
      });
    } catch (RuntimeException e) {
      throw new ProfileException("Failed to save profile " + snapshot.playerId(), e);
    }
  }

  private void deleteNow(UUID playerId) {
    try {
      database.transaction(session -> {
        PlayerProfileEntity entity = session.find(PlayerProfileEntity.class, playerId);
        if (entity != null) {
          session.remove(entity);
        }
        return null;
      });
    } catch (RuntimeException e) {
      throw new ProfileException("Failed to delete profile " + playerId, e);
    }
  }

  private static PlayerProfile toDomain(PlayerProfileEntity entity) {
    return new PlayerProfile(entity.playerId(), entity.name(), Money.of(entity.balance()),
        entity.level(), entity.experience(), entity.createdAt());
  }

  private record ProfileSnapshot(
      UUID playerId, String name, long balance, int level, long experience, Instant createdAt) {}
}
