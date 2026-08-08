package com.birditch.raidcore.api.economy;

import com.birditch.raidcore.common.result.Result;
import java.util.Optional;
import java.util.UUID;

/// Balance operations against the cached profile. Main-thread only.
///
/// Operations fail (as a [Result] failure) when the player's profile is not
/// loaded, or on withdrawal with insufficient funds.
public interface EconomyService {

    Optional<Money> balance(UUID playerId);

    Result<Transaction> deposit(UUID playerId, Money amount, TransactionType type, String description);

    Result<Transaction> withdraw(UUID playerId, Money amount, TransactionType type, String description);
}
