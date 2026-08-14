package com.birditch.escapefromcraftov.economy;

import com.birditch.escapefromcraftov.api.economy.EconomyDirection;
import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.economy.Transaction;
import com.birditch.escapefromcraftov.api.economy.TransactionType;
import com.birditch.escapefromcraftov.common.result.Result;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Asynchronous durable transaction boundary for the economy service.
public interface EconomyRepository {

  CompletableFuture<Result<Transaction>> apply(UUID transactionId, UUID playerId,
      EconomyDirection direction, Money amount, TransactionType type,
      String description, Instant timestamp);
}
