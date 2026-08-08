package com.birditch.raidcore.economy;

import com.birditch.raidcore.api.economy.Currency;
import com.birditch.raidcore.api.economy.EconomyService;
import com.birditch.raidcore.api.economy.Money;
import com.birditch.raidcore.api.economy.Transaction;
import com.birditch.raidcore.api.economy.TransactionType;
import com.birditch.raidcore.api.profile.PlayerProfile;
import com.birditch.raidcore.api.profile.ProfileService;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.result.Result;
import com.birditch.raidcore.common.time.TimeSource;
import java.util.Optional;
import java.util.UUID;

/// [EconomyService] over the profile cache.
///
/// Threading: every public method enforces the main-thread rule via the
/// injected [RaidScheduler] and throws [IllegalStateException] off-thread.
/// Balance changes mutate the cached [PlayerProfile] directly; persistence is
/// the profile layer's concern.
public final class DefaultEconomyService implements EconomyService {

    private static final String MAIN_THREAD_MESSAGE =
            "EconomyService must be called from the main thread";
    private static final String PROFILE_NOT_LOADED = "Profile not loaded";
    private static final String INSUFFICIENT_FUNDS = "Insufficient funds";

    private final ProfileService profiles;
    private final RaidScheduler scheduler;
    private final TimeSource timeSource;

    public DefaultEconomyService(ProfileService profiles, RaidScheduler scheduler, TimeSource timeSource) {
        this.profiles = Preconditions.checkNotNull(profiles, "profiles");
        this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
        this.timeSource = Preconditions.checkNotNull(timeSource, "timeSource");
    }

    @Override
    public Optional<Money> balance(UUID playerId) {
        requireMainThread();
        Preconditions.checkNotNull(playerId, "playerId");
        return profiles.find(playerId).map(PlayerProfile::balance);
    }

    @Override
    public Result<Transaction> deposit(UUID playerId, Money amount, TransactionType type, String description) {
        requireMainThread();
        ChangeRequest request = validated(playerId, amount, type, description);
        Optional<PlayerProfile> found = profiles.find(playerId);
        if (found.isEmpty()) {
            return Result.fail(PROFILE_NOT_LOADED);
        }
        PlayerProfile profile = found.get();
        return commit(profile, profile.balance().plus(amount), request);
    }

    @Override
    public Result<Transaction> withdraw(UUID playerId, Money amount, TransactionType type, String description) {
        requireMainThread();
        ChangeRequest request = validated(playerId, amount, type, description);
        Optional<PlayerProfile> found = profiles.find(playerId);
        if (found.isEmpty()) {
            return Result.fail(PROFILE_NOT_LOADED);
        }
        PlayerProfile profile = found.get();
        if (!profile.balance().atLeast(amount)) {
            return Result.fail(INSUFFICIENT_FUNDS);
        }
        return commit(profile, profile.balance().minus(amount), request);
    }

    private void requireMainThread() {
        if (!scheduler.isMainThread()) {
            throw new IllegalStateException(MAIN_THREAD_MESSAGE);
        }
    }

    private static ChangeRequest validated(UUID playerId, Money amount, TransactionType type, String description) {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(amount, "amount");
        Preconditions.checkNotNull(type, "type");
        Preconditions.checkNotNull(description, "description");
        return new ChangeRequest(amount, type, description);
    }

    private Result<Transaction> commit(PlayerProfile profile, Money newBalance, ChangeRequest request) {
        profile.setBalance(newBalance);
        Transaction transaction = new Transaction(
                UUID.randomUUID(),
                profile.playerId(),
                request.type(),
                Currency.COINS,
                request.amount(),
                newBalance,
                timeSource.now(),
                request.description());
        return Result.ok(transaction);
    }

    private record ChangeRequest(Money amount, TransactionType type, String description) {
    }
}
