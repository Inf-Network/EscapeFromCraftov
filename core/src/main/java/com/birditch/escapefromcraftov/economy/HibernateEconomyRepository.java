package com.birditch.escapefromcraftov.economy;

import com.birditch.escapefromcraftov.api.economy.Currency;
import com.birditch.escapefromcraftov.api.economy.EconomyDirection;
import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.economy.Transaction;
import com.birditch.escapefromcraftov.api.economy.TransactionType;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.core.database.Database;
import com.birditch.escapefromcraftov.core.database.entity.EconomyTransactionEntity;
import com.birditch.escapefromcraftov.core.database.entity.PlayerProfileEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/// Hibernate/PostgreSQL economy repository.
///
/// The player profile row is locked for update so concurrent Game/Lobby
/// processes cannot overwrite one another. Balance mutation and immutable
/// audit row insertion commit in the same transaction. Reusing the same
/// transaction ID with the same request is idempotent; reusing it for another
/// request is rejected as a programming error.
public final class HibernateEconomyRepository implements EconomyRepository {

  static final String PROFILE_NOT_FOUND = "Profile not found";
  static final String INSUFFICIENT_FUNDS = "Insufficient funds";

  private final Database database;
  private final ExecutorService executor;

  public HibernateEconomyRepository(Database database, ExecutorService executor) {
    this.database = Preconditions.checkNotNull(database, "database");
    this.executor = Preconditions.checkNotNull(executor, "executor");
  }

  @Override
  public CompletableFuture<Result<Transaction>> apply(UUID transactionId, UUID playerId,
      EconomyDirection direction, Money amount, TransactionType type,
      String description, Instant timestamp) {
    Preconditions.checkNotNull(transactionId, "transactionId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(direction, "direction");
    Preconditions.checkNotNull(amount, "amount");
    Preconditions.checkNotNull(type, "type");
    Preconditions.checkNotNull(description, "description");
    Preconditions.checkNotNull(timestamp, "timestamp");
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      EconomyTransactionEntity existing =
          session.find(EconomyTransactionEntity.class, transactionId);
      if (existing != null) {
        verifySameRequest(existing, playerId, direction, amount, type, description);
        return Result.ok(toTransaction(existing));
      }
      PlayerProfileEntity profile = session.find(
          PlayerProfileEntity.class, playerId, LockModeType.PESSIMISTIC_WRITE);
      if (profile == null) return Result.fail(PROFILE_NOT_FOUND);
      long balanceAfter;
      if (direction == EconomyDirection.CREDIT) {
        balanceAfter = Math.addExact(profile.balance(), amount.units());
      } else {
        if (profile.balance() < amount.units()) return Result.fail(INSUFFICIENT_FUNDS);
        balanceAfter = profile.balance() - amount.units();
      }
      profile.updateBalance(balanceAfter, timestamp);
      EconomyTransactionEntity entity = new EconomyTransactionEntity(
          transactionId, playerId, type, direction, Currency.COINS,
          amount.units(), balanceAfter, description, timestamp);
      session.persist(entity);
      session.flush();
      return Result.ok(toTransaction(entity));
    }), executor);
  }

  private static void verifySameRequest(EconomyTransactionEntity existing, UUID playerId,
      EconomyDirection direction, Money amount, TransactionType type, String description) {
    boolean same = existing.playerId().equals(playerId)
        && existing.direction() == direction
        && existing.amount() == amount.units()
        && existing.type() == type
        && existing.currency() == Currency.COINS
        && existing.description().equals(description);
    if (!same) {
      throw new IllegalStateException(
          "Economy transaction id was reused with different request data: "
              + existing.transactionId());
    }
  }

  private static Transaction toTransaction(EconomyTransactionEntity entity) {
    return new Transaction(entity.transactionId(), entity.playerId(), entity.type(),
        entity.direction(), entity.currency(), Money.of(entity.amount()),
        Money.of(entity.balanceAfter()), entity.createdAt(), entity.description());
  }
}
