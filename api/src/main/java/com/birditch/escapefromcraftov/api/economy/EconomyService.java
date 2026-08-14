package com.birditch.escapefromcraftov.api.economy;

import com.birditch.escapefromcraftov.common.result.Result;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Durable PostgreSQL balance operations. Calls are initiated on the main
/// thread; returned futures complete after the balance and audit row commit.
///
/// Operations fail (as a [Result] failure) when the player's profile is not
/// loaded, or on withdrawal with insufficient funds.
public interface EconomyService {

    Optional<Money> balance(UUID playerId);

    default CompletableFuture<Result<Transaction>> deposit(
            UUID playerId, Money amount, TransactionType type, String description) {
        return deposit(UUID.randomUUID(), playerId, amount, type, description);
    }

    CompletableFuture<Result<Transaction>> deposit(
            UUID transactionId, UUID playerId, Money amount,
            TransactionType type, String description);

    default CompletableFuture<Result<Transaction>> withdraw(
            UUID playerId, Money amount, TransactionType type, String description) {
        return withdraw(UUID.randomUUID(), playerId, amount, type, description);
    }

    CompletableFuture<Result<Transaction>> withdraw(
            UUID transactionId, UUID playerId, Money amount,
            TransactionType type, String description);
}
