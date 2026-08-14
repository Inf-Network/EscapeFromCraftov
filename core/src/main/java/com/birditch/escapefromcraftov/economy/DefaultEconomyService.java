package com.birditch.escapefromcraftov.economy;

import com.birditch.escapefromcraftov.api.economy.EconomyDirection;
import com.birditch.escapefromcraftov.api.economy.EconomyService;
import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.economy.Transaction;
import com.birditch.escapefromcraftov.api.economy.TransactionType;
import com.birditch.escapefromcraftov.api.profile.PlayerProfile;
import com.birditch.escapefromcraftov.api.profile.ProfileService;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.common.time.TimeSource;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Main-thread facade over a durable asynchronous economy repository.
///
/// PostgreSQL is authoritative. The online profile cache changes only after
/// the balance row and audit row commit, and that cache update is always
/// marshalled back to the main thread.
public final class DefaultEconomyService implements EconomyService {

  private static final String MAIN_THREAD_MESSAGE =
      "EconomyService must be called from the main thread";
  private static final String PROFILE_NOT_LOADED = "Profile not loaded";

  private final ProfileService profiles;
  private final CraftovScheduler scheduler;
  private final TimeSource timeSource;
  private final EconomyRepository repository;

  public DefaultEconomyService(ProfileService profiles, CraftovScheduler scheduler,
      TimeSource timeSource, EconomyRepository repository) {
    this.profiles = Preconditions.checkNotNull(profiles, "profiles");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.timeSource = Preconditions.checkNotNull(timeSource, "timeSource");
    this.repository = Preconditions.checkNotNull(repository, "repository");
  }

  @Override
  public Optional<Money> balance(UUID playerId) {
    requireMainThread();
    Preconditions.checkNotNull(playerId, "playerId");
    return profiles.find(playerId).map(PlayerProfile::balance);
  }

  @Override
  public CompletableFuture<Result<Transaction>> deposit(UUID transactionId, UUID playerId,
      Money amount, TransactionType type, String description) {
    return change(transactionId, playerId, EconomyDirection.CREDIT, amount, type, description);
  }

  @Override
  public CompletableFuture<Result<Transaction>> withdraw(UUID transactionId, UUID playerId,
      Money amount, TransactionType type, String description) {
    return change(transactionId, playerId, EconomyDirection.DEBIT, amount, type, description);
  }

  private CompletableFuture<Result<Transaction>> change(UUID transactionId, UUID playerId,
      EconomyDirection direction, Money amount, TransactionType type, String description) {
    requireMainThread();
    Preconditions.checkNotNull(transactionId, "transactionId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(direction, "direction");
    Preconditions.checkNotNull(amount, "amount");
    Preconditions.checkNotNull(type, "type");
    Preconditions.checkNotNull(description, "description");
    Preconditions.checkArgument(!description.isBlank(), "description must not be blank");
    Preconditions.checkArgument(description.length() <= 200,
        "description must not exceed 200 characters");
    if (profiles.find(playerId).isEmpty()) {
      return CompletableFuture.completedFuture(Result.fail(PROFILE_NOT_LOADED));
    }
    CompletableFuture<Result<Transaction>> completion = new CompletableFuture<>();
    repository.apply(transactionId, playerId, direction, amount, type, description,
        timeSource.now()).whenComplete((result, failure) -> scheduler.runSync(() -> {
          if (failure != null) {
            completion.completeExceptionally(failure);
            return;
          }
          result.value().ifPresent(transaction -> profiles.find(playerId)
              .ifPresent(profile -> profile.setBalance(transaction.balanceAfter())));
          completion.complete(result);
        }));
    return completion;
  }

  private void requireMainThread() {
    if (!scheduler.isMainThread()) throw new IllegalStateException(MAIN_THREAD_MESSAGE);
  }
}
