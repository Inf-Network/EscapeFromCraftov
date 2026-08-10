package com.birditch.raidcore.api.economy;

import com.birditch.raidcore.common.check.Preconditions;
import java.time.Instant;
import java.util.UUID;

/// Immutable record of a completed balance change.
public record Transaction(
        UUID transactionId,
        UUID playerId,
        TransactionType type,
        EconomyDirection direction,
        Currency currency,
        Money amount,
        Money balanceAfter,
        Instant timestamp,
        String description) {

    public Transaction {
        Preconditions.checkNotNull(transactionId, "transactionId");
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(type, "type");
        Preconditions.checkNotNull(direction, "direction");
        Preconditions.checkNotNull(currency, "currency");
        Preconditions.checkNotNull(amount, "amount");
        Preconditions.checkNotNull(balanceAfter, "balanceAfter");
        Preconditions.checkNotNull(timestamp, "timestamp");
        Preconditions.checkNotNull(description, "description");
    }
}
