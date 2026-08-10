package com.birditch.raidcore.profile;

import com.birditch.raidcore.api.profile.ProfileService;
import com.birditch.raidcore.api.profile.ProgressionService;
import com.birditch.raidcore.api.profile.ProgressionUpdate;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.result.Result;
import com.birditch.raidcore.common.time.TimeSource;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Main-thread facade for durable, idempotent XP awards.
public final class DefaultProgressionService implements ProgressionService {

  private final ProfileService profiles;
  private final ProgressionRepository repository;
  private final RaidScheduler scheduler;
  private final TimeSource timeSource;

  public DefaultProgressionService(ProfileService profiles, ProgressionRepository repository,
      RaidScheduler scheduler, TimeSource timeSource) {
    this.profiles = Preconditions.checkNotNull(profiles, "profiles");
    this.repository = Preconditions.checkNotNull(repository, "repository");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.timeSource = Preconditions.checkNotNull(timeSource, "timeSource");
  }

  @Override
  public CompletableFuture<Result<ProgressionUpdate>> award(UUID transactionId, UUID playerId,
      long experience, String reason) {
    if (!scheduler.isMainThread()) {
      throw new IllegalStateException("ProgressionService must be called from the main thread");
    }
    Preconditions.checkNotNull(transactionId, "transactionId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkArgument(experience > 0, "experience must be positive");
    Preconditions.checkNotNull(reason, "reason");
    Preconditions.checkArgument(!reason.isBlank(), "reason must not be blank");
    Preconditions.checkArgument(reason.length() <= 200, "reason must not exceed 200 characters");
    CompletableFuture<Result<ProgressionUpdate>> completion = new CompletableFuture<>();
    repository.award(transactionId, playerId, experience, reason, timeSource.now())
        .whenComplete((result, failure) -> scheduler.runSync(() -> {
          if (failure != null) {
            completion.completeExceptionally(failure);
            return;
          }
          result.value().ifPresent(update -> profiles.find(playerId).ifPresent(profile ->
              profile.setProgression(update.experienceAfter(), update.levelAfter())));
          completion.complete(result);
        }));
    return completion;
  }
}
