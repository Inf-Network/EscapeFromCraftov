package com.birditch.raidcore.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.economy.Currency;
import com.birditch.raidcore.api.economy.EconomyService;
import com.birditch.raidcore.api.economy.Money;
import com.birditch.raidcore.api.economy.Transaction;
import com.birditch.raidcore.api.economy.TransactionType;
import com.birditch.raidcore.api.testing.ManualScheduler;
import com.birditch.raidcore.api.testing.MutableTimeSource;
import com.birditch.raidcore.common.result.Result;
import com.birditch.raidcore.profile.DefaultProfileService;
import com.birditch.raidcore.profile.InMemoryProfileRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("DefaultEconomyService")
class DefaultEconomyServiceTest {

    private static final String MAIN_THREAD_MESSAGE =
            "EconomyService must be called from the main thread";

    private final MutableTimeSource timeSource = new MutableTimeSource(1_000L);
    private final ManualScheduler scheduler = new ManualScheduler();
    private final DefaultProfileService profiles = new DefaultProfileService(
            new InMemoryProfileRepository(), timeSource, Logger.getLogger("DefaultEconomyServiceTest"));
    private final DefaultEconomyService economy =
            new DefaultEconomyService(profiles, scheduler, timeSource);

    private UUID loadedPlayer() {
        UUID playerId = UUID.randomUUID();
        profiles.load(playerId, "Steve").join();
        return playerId;
    }

    @Nested
    @DisplayName("balance")
    class Balance {

        @Test
        @DisplayName("returns the cached balance for a loaded profile")
        void returnsCachedBalance() {
            UUID playerId = loadedPlayer();
            profiles.require(playerId).setBalance(Money.of(750));

            assertEquals(Optional.of(Money.of(750)), economy.balance(playerId));
        }

        @Test
        @DisplayName("is empty when the profile is not loaded")
        void emptyWhenNotLoaded() {
            assertEquals(Optional.empty(), economy.balance(UUID.randomUUID()));
        }
    }

    @Nested
    @DisplayName("deposit")
    class Deposit {

        @Test
        @DisplayName("adds to the balance and records the transaction")
        void addsToBalance() {
            UUID playerId = loadedPlayer();

            Result<Transaction> result =
                    economy.deposit(playerId, Money.of(2_500), TransactionType.RAID_REWARD, "Raid reward");

            assertTrue(result.isSuccess());
            Transaction transaction = result.value().orElseThrow();
            assertEquals(playerId, transaction.playerId());
            assertEquals(TransactionType.RAID_REWARD, transaction.type());
            assertEquals(Currency.COINS, transaction.currency());
            assertEquals(Money.of(2_500), transaction.amount());
            assertEquals(Money.of(2_500), transaction.balanceAfter());
            assertEquals("Raid reward", transaction.description());
            assertEquals(Money.of(2_500), profiles.require(playerId).balance());
        }

        @Test
        @DisplayName("accumulates over multiple deposits with unique transaction ids")
        void accumulates() {
            UUID playerId = loadedPlayer();

            Transaction first = economy.deposit(playerId, Money.of(1_000), TransactionType.ADMIN, "first")
                    .value().orElseThrow();
            Transaction second = economy.deposit(playerId, Money.of(500), TransactionType.ADMIN, "second")
                    .value().orElseThrow();

            assertEquals(Money.of(1_500), second.balanceAfter());
            assertEquals(Money.of(1_500), profiles.require(playerId).balance());
            assertNotEquals(first.transactionId(), second.transactionId());
        }

        @Test
        @DisplayName("stamps the transaction with the injected time source")
        void usesTimeSource() {
            UUID playerId = loadedPlayer();
            timeSource.advance(Duration.ofMinutes(5));

            Transaction transaction = economy.deposit(playerId, Money.of(1), TransactionType.ADMIN, "timed")
                    .value().orElseThrow();

            assertEquals(Instant.ofEpochMilli(1_000L + Duration.ofMinutes(5).toMillis()),
                    transaction.timestamp());
        }

        @Test
        @DisplayName("fails when the profile is not loaded")
        void failsWhenNotLoaded() {
            Result<Transaction> result =
                    economy.deposit(UUID.randomUUID(), Money.of(100), TransactionType.ADMIN, "orphan");

            assertFalse(result.isSuccess());
            assertEquals(Optional.of("Profile not loaded"), result.error());
        }
    }

    @Nested
    @DisplayName("withdraw")
    class Withdraw {

        @Test
        @DisplayName("subtracts from the balance and records the transaction")
        void subtractsFromBalance() {
            UUID playerId = loadedPlayer();
            profiles.require(playerId).setBalance(Money.of(5_000));

            Result<Transaction> result =
                    economy.withdraw(playerId, Money.of(2_000), TransactionType.SHOP_BUY, "Shop purchase");

            assertTrue(result.isSuccess());
            Transaction transaction = result.value().orElseThrow();
            assertEquals(Money.of(2_000), transaction.amount());
            assertEquals(Money.of(3_000), transaction.balanceAfter());
            assertEquals(TransactionType.SHOP_BUY, transaction.type());
            assertEquals(Money.of(3_000), profiles.require(playerId).balance());
        }

        @Test
        @DisplayName("allows withdrawing the exact balance down to zero")
        void allowsExactBalance() {
            UUID playerId = loadedPlayer();
            profiles.require(playerId).setBalance(Money.of(300));

            Result<Transaction> result =
                    economy.withdraw(playerId, Money.of(300), TransactionType.MARKET_BUY, "all in");

            assertTrue(result.isSuccess());
            assertTrue(profiles.require(playerId).balance().isZero());
        }

        @Test
        @DisplayName("fails on insufficient funds and leaves the balance untouched")
        void failsOnInsufficientFunds() {
            UUID playerId = loadedPlayer();
            profiles.require(playerId).setBalance(Money.of(100));

            Result<Transaction> result =
                    economy.withdraw(playerId, Money.of(101), TransactionType.SHOP_BUY, "too much");

            assertFalse(result.isSuccess());
            assertEquals(Optional.of("Insufficient funds"), result.error());
            assertEquals(Money.of(100), profiles.require(playerId).balance());
        }

        @Test
        @DisplayName("fails when the profile is not loaded")
        void failsWhenNotLoaded() {
            Result<Transaction> result =
                    economy.withdraw(UUID.randomUUID(), Money.of(1), TransactionType.ADMIN, "orphan");

            assertFalse(result.isSuccess());
            assertEquals(Optional.of("Profile not loaded"), result.error());
        }
    }

    @Nested
    @DisplayName("threading")
    class Threading {

        @ParameterizedTest(name = "{0} throws off the main thread")
        @MethodSource("com.birditch.raidcore.economy.DefaultEconomyServiceTest#mainThreadOnlyCalls")
        void throwsOffMainThread(String name, Consumer<EconomyService> call) {
            UUID playerId = loadedPlayer();
            scheduler.setMainThread(false);

            IllegalStateException thrown =
                    assertThrows(IllegalStateException.class, () -> call.accept(economy), name);

            assertEquals(MAIN_THREAD_MESSAGE, thrown.getMessage());
            scheduler.setMainThread(true);
            assertEquals(Optional.of(Money.ZERO), economy.balance(playerId));
        }
    }

    static Stream<Arguments> mainThreadOnlyCalls() {
        UUID playerId = UUID.randomUUID();
        return Stream.of(
                Arguments.of("balance", (Consumer<EconomyService>) service -> service.balance(playerId)),
                Arguments.of("deposit", (Consumer<EconomyService>) service ->
                        service.deposit(playerId, Money.of(1), TransactionType.ADMIN, "deposit")),
                Arguments.of("withdraw", (Consumer<EconomyService>) service ->
                        service.withdraw(playerId, Money.of(1), TransactionType.ADMIN, "withdraw")));
    }
}
