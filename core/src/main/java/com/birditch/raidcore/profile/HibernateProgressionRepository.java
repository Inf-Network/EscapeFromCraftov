package com.birditch.raidcore.profile;

import com.birditch.raidcore.api.profile.ProgressionCurve;
import com.birditch.raidcore.api.profile.ProgressionUpdate;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.result.Result;
import com.birditch.raidcore.core.database.Database;
import com.birditch.raidcore.core.database.entity.PlayerProfileEntity;
import com.birditch.raidcore.core.database.entity.ProgressionTransactionEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/// PostgreSQL progression repository with row locking and idempotent awards.
public final class HibernateProgressionRepository implements ProgressionRepository {

  private final Database database;
  private final ExecutorService executor;

  public HibernateProgressionRepository(Database database, ExecutorService executor) {
    this.database = Preconditions.checkNotNull(database, "database");
    this.executor = Preconditions.checkNotNull(executor, "executor");
  }

  @Override
  public CompletableFuture<Result<ProgressionUpdate>> award(UUID transactionId, UUID playerId,
      long experience, String reason, Instant timestamp) {
    Preconditions.checkNotNull(transactionId, "transactionId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkArgument(experience > 0, "experience must be positive");
    Preconditions.checkNotNull(reason, "reason");
    Preconditions.checkArgument(!reason.isBlank(), "reason must not be blank");
    Preconditions.checkNotNull(timestamp, "timestamp");
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      ProgressionTransactionEntity existing =
          session.find(ProgressionTransactionEntity.class, transactionId);
      if (existing != null) {
        if (!existing.playerId().equals(playerId)
            || existing.awardedExperience() != experience
            || !existing.reason().equals(reason)) {
          throw new IllegalStateException(
              "Progression transaction id reused with different data: " + transactionId);
        }
        return Result.ok(toUpdate(existing));
      }
      PlayerProfileEntity profile = session.find(
          PlayerProfileEntity.class, playerId, LockModeType.PESSIMISTIC_WRITE);
      if (profile == null) return Result.fail("Profile not found");
      long experienceAfter = Math.addExact(profile.experience(), experience);
      int levelBefore = profile.level();
      int levelAfter = ProgressionCurve.levelForExperience(experienceAfter);
      profile.updateProgression(experienceAfter, levelAfter, timestamp);
      ProgressionTransactionEntity entity = new ProgressionTransactionEntity(
          transactionId, playerId, experience, experienceAfter, levelBefore,
          levelAfter, reason, timestamp);
      session.persist(entity);
      session.flush();
      return Result.ok(toUpdate(entity));
    }), executor);
  }

  private static ProgressionUpdate toUpdate(ProgressionTransactionEntity entity) {
    return new ProgressionUpdate(entity.transactionId(), entity.playerId(),
        entity.awardedExperience(), entity.experienceAfter(), entity.levelBefore(),
        entity.levelAfter(), entity.reason(), entity.createdAt());
  }
}
