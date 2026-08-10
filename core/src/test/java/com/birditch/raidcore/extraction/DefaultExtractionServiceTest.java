package com.birditch.raidcore.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.event.extraction.PlayerExtractionReadyEvent;
import com.birditch.raidcore.api.event.extraction.PlayerExtractionLockedEvent;
import com.birditch.raidcore.api.event.extraction.PlayerExtractionAuthorizationFailedEvent;
import com.birditch.raidcore.api.event.game.RaidEndedEvent;
import com.birditch.raidcore.api.extraction.ExtractionCondition;
import com.birditch.raidcore.api.extraction.ExtractionContext;
import com.birditch.raidcore.api.extraction.ExtractionFailures;
import com.birditch.raidcore.api.extraction.ExtractionPoint;
import com.birditch.raidcore.api.extraction.ExtractionPointId;
import com.birditch.raidcore.api.extraction.ExtractionSession;
import com.birditch.raidcore.api.extraction.ExtractionState;
import com.birditch.raidcore.api.extraction.ExtractionType;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.game.RaidPlayer;
import com.birditch.raidcore.api.game.RaidResult;
import com.birditch.raidcore.api.testing.ManualScheduler;
import com.birditch.raidcore.api.testing.MutableTimeSource;
import com.birditch.raidcore.common.result.Result;
import com.birditch.raidcore.core.event.SimpleEventBus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("DefaultExtractionService")
class DefaultExtractionServiceTest {

    private static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final RaidGameId GAME_ID = RaidGameId.of("raid-1");
    private static final ExtractionPointId POINT_ID = ExtractionPointId.of("north_gate");
    private static final ExtractionPointId LOCKED_POINT_ID = ExtractionPointId.of("locked_gate");
    private static final Duration POINT_DURATION = Duration.ofSeconds(10);

    private ManualScheduler scheduler;
    private MutableTimeSource timeSource;
    private SimpleEventBus eventBus;
    private FakeGameService games;
    private FakeRaidGame game;
    private DefaultExtractionService service;
    private List<PlayerExtractionReadyEvent> published;
    private List<PlayerExtractionLockedEvent> locked;
    private List<PlayerExtractionAuthorizationFailedEvent> authorizationFailed;

    @BeforeEach
    void setUp() {
        scheduler = new ManualScheduler();
        timeSource = new MutableTimeSource(0L);
        eventBus = new SimpleEventBus(
                Logger.getLogger(DefaultExtractionServiceTest.class.getName()));
        games = new FakeGameService();
        game = new FakeRaidGame(GAME_ID, RaidMapId.of("debug_zone"), Instant.EPOCH);
        game.addPlayer(new RaidPlayer(PLAYER_ID, "Alice"));
        game.addExtractionPoint(point(POINT_ID, List.of(), ExtractionState.AVAILABLE));
        game.addExtractionPoint(point(LOCKED_POINT_ID, List.of(), ExtractionState.LOCKED));
        games.add(game);
        service = new DefaultExtractionService(games, scheduler, eventBus, timeSource);
        published = new ArrayList<>();
        locked = new ArrayList<>();
        authorizationFailed = new ArrayList<>();
        eventBus.subscribe(PlayerExtractionReadyEvent.class, published::add);
        eventBus.subscribe(PlayerExtractionLockedEvent.class, locked::add);
        eventBus.subscribe(PlayerExtractionAuthorizationFailedEvent.class,
                authorizationFailed::add);
    }

    @Nested
    @DisplayName("begin")
    class Begin {

        @Test
        @DisplayName("starts an EXTRACTING session bound to player, game, point and clock")
        void startsExtractingSession() {
            timeSource.set(5_000L);

            Result<ExtractionSession> result = service.begin(PLAYER_ID, GAME_ID, POINT_ID);

            assertTrue(result.isSuccess());
            ExtractionSession session = result.orElseThrow(IllegalStateException::new);
            assertEquals(ExtractionState.EXTRACTING, session.state());
            assertEquals(PLAYER_ID, session.playerId());
            assertEquals(GAME_ID, session.gameId());
            assertEquals(POINT_ID, session.pointId());
            assertEquals(Instant.ofEpochMilli(5_000L), session.startedAt());
            assertEquals(Optional.of(session), service.activeSession(PLAYER_ID));
            assertEquals(1, service.activeCount());
        }

        @Test
        void failsWhenGameNotFound() {
            assertFailure(service.begin(PLAYER_ID, RaidGameId.of("missing"), POINT_ID),
                    ExtractionFailures.GAME_NOT_FOUND);
        }

        @ParameterizedTest
        @EnumSource(value = RaidGameState.class, names = "RUNNING", mode = EnumSource.Mode.EXCLUDE)
        void failsWhenGameIsNotRunning(RaidGameState state) {
            game.setState(state);
            assertFailure(service.begin(PLAYER_ID, GAME_ID, POINT_ID),
                    ExtractionFailures.GAME_NOT_RUNNING);
        }

        @Test
        void failsWhenPlayerIsNotInTheGame() {
            assertFailure(service.begin(OTHER_PLAYER_ID, GAME_ID, POINT_ID),
                    ExtractionFailures.PLAYER_NOT_IN_GAME);
        }

        @Test
        void failsWhenExtractionPointIsUnknown() {
            assertFailure(service.begin(PLAYER_ID, GAME_ID, ExtractionPointId.of("south_gate")),
                    ExtractionFailures.POINT_NOT_FOUND);
        }

        @Test
        void failsWhenExtractionPointIsLocked() {
            assertFailure(service.begin(PLAYER_ID, GAME_ID, LOCKED_POINT_ID),
                    ExtractionFailures.POINT_LOCKED);
        }

        @Test
        void failsWhenAlreadyExtracting() {
            assertTrue(service.begin(PLAYER_ID, GAME_ID, POINT_ID).isSuccess());

            assertFailure(service.begin(PLAYER_ID, GAME_ID, POINT_ID),
                    ExtractionFailures.ALREADY_EXTRACTING);
            assertEquals(1, service.activeCount());
        }

        @Test
        void failsWithDescriptionWhenConditionBlocks() {
            ExtractionPointId gated = ExtractionPointId.of("gated");
            game.addExtractionPoint(point(gated,
                    List.of(condition("carry a key card", false)), ExtractionState.AVAILABLE));

            assertFailure(service.begin(PLAYER_ID, GAME_ID, gated),
                    ExtractionFailures.blocked("carry a key card"));
            assertEquals(0, service.activeCount());
        }

        @Test
        void succeedsWhenAllConditionsAreSatisfied() {
            ExtractionPointId gated = ExtractionPointId.of("gated");
            game.addExtractionPoint(point(gated,
                    List.of(condition("carry a key card", true)), ExtractionState.AVAILABLE));

            assertTrue(service.begin(PLAYER_ID, GAME_ID, gated).isSuccess());
        }

        @Test
        void passesPlayerAndGameToConditions() {
            List<ExtractionContext> seen = new ArrayList<>();
            ExtractionCondition recording = new ExtractionCondition() {
                @Override
                public String describe() {
                    return "recording";
                }

                @Override
                public boolean isSatisfied(ExtractionContext context) {
                    seen.add(context);
                    return true;
                }
            };
            ExtractionPointId gated = ExtractionPointId.of("gated");
            game.addExtractionPoint(point(gated, List.of(recording), ExtractionState.AVAILABLE));

            assertTrue(service.begin(PLAYER_ID, GAME_ID, gated).isSuccess());
            assertEquals(List.of(new ExtractionContext(PLAYER_ID, GAME_ID)), seen);
        }

        @Test
        void throwsOffTheMainThread() {
            scheduler.setMainThread(false);
            assertThrows(IllegalStateException.class,
                    () -> service.begin(PLAYER_ID, GAME_ID, POINT_ID));
        }
    }

    @Nested
    @DisplayName("completion")
    class Completion {

        @Test
        void beginsSettlementAfterPointDuration() {
            ExtractionSession session = service.begin(PLAYER_ID, GAME_ID, POINT_ID)
                    .orElseThrow(IllegalStateException::new);

            scheduler.advanceBy(POINT_DURATION);

            assertEquals(ExtractionState.SETTLING, session.state());
            assertEquals(Optional.of(session), service.activeSession(PLAYER_ID));
            assertEquals(1, service.activeCount());
            assertEquals(List.of(new PlayerExtractionReadyEvent(GAME_ID, PLAYER_ID, POINT_ID)),
                    published);
            assertEquals(List.of(new PlayerExtractionLockedEvent(GAME_ID, PLAYER_ID, POINT_ID)),
                    locked);
        }

        @Test
        void failsAndUnlocksWhenDurableAuthorizationIsRejected() {
            ExtractionPointId paid = ExtractionPointId.of("paid_gate");
            ExtractionCondition rejected = new ExtractionCondition() {
                @Override public String describe() { return "付费撤离"; }
                @Override public boolean isSatisfied(ExtractionContext context) { return true; }
                @Override public CompletableFuture<Boolean> authorize(ExtractionContext context) {
                    return CompletableFuture.completedFuture(false);
                }
            };
            game.addExtractionPoint(point(paid, List.of(rejected), ExtractionState.AVAILABLE));
            ExtractionSession session = service.begin(PLAYER_ID, GAME_ID, paid)
                    .orElseThrow(IllegalStateException::new);

            scheduler.advanceBy(POINT_DURATION);

            assertEquals(ExtractionState.FAILED, session.state());
            assertTrue(published.isEmpty());
            assertEquals(1, authorizationFailed.size());
            assertEquals(0, service.activeCount());
        }

        @Test
        void retriesTransientAuthorizationFailureWithoutPublishingTwice() {
            ExtractionPointId paid = ExtractionPointId.of("paid_gate");
            AtomicInteger attempts = new AtomicInteger();
            ExtractionCondition transientFailure = new ExtractionCondition() {
                @Override public String describe() { return "付费撤离"; }
                @Override public boolean isSatisfied(ExtractionContext context) { return true; }
                @Override public CompletableFuture<Boolean> authorize(ExtractionContext context) {
                    if (attempts.getAndIncrement() == 0) {
                        return CompletableFuture.failedFuture(new IllegalStateException("db down"));
                    }
                    return CompletableFuture.completedFuture(true);
                }
            };
            game.addExtractionPoint(point(
                    paid, List.of(transientFailure), ExtractionState.AVAILABLE));
            ExtractionSession session = service.begin(PLAYER_ID, GAME_ID, paid)
                    .orElseThrow(IllegalStateException::new);

            scheduler.advanceBy(POINT_DURATION);
            assertEquals(ExtractionState.SETTLING, session.state());
            assertTrue(published.isEmpty());

            scheduler.advanceBy(Duration.ofSeconds(5));
            assertEquals(1, published.size());
            assertEquals(2, attempts.get());
        }

        @Test
        void doesNotCompleteBeforeTheDurationElapses() {
            ExtractionSession session = service.begin(PLAYER_ID, GAME_ID, POINT_ID)
                    .orElseThrow(IllegalStateException::new);

            scheduler.advanceBy(POINT_DURATION.minusMillis(1));

            assertEquals(ExtractionState.EXTRACTING, session.state());
            assertEquals(1, service.activeCount());
            assertTrue(published.isEmpty());

            scheduler.advanceBy(Duration.ofMillis(1));

            assertEquals(ExtractionState.SETTLING, session.state());
        }

        @Test
        void keepsSettlingSessionVisibleWhilePublishingTheEvent() {
            List<Integer> activeCountsAtPublish = new ArrayList<>();
            eventBus.subscribe(PlayerExtractionReadyEvent.class,
                    _ -> activeCountsAtPublish.add(service.activeCount()));
            assertTrue(service.begin(PLAYER_ID, GAME_ID, POINT_ID).isSuccess());

            scheduler.advanceBy(POINT_DURATION);

            assertEquals(List.of(1), activeCountsAtPublish);
        }

        @Test
        void allowsANewExtractionOnlyAfterDurableSettlementCompletes() {
            assertTrue(service.begin(PLAYER_ID, GAME_ID, POINT_ID).isSuccess());
            scheduler.advanceBy(POINT_DURATION);

            assertFailure(service.begin(PLAYER_ID, GAME_ID, POINT_ID),
                    ExtractionFailures.ALREADY_EXTRACTING);
            assertTrue(service.completeSettlement(PLAYER_ID, GAME_ID, POINT_ID));
            assertTrue(service.begin(PLAYER_ID, GAME_ID, POINT_ID).isSuccess());
            assertEquals(1, service.activeCount());
        }

        @Test
        void durableSettlementAcknowledgementIsBoundToTheExactAttempt() {
            ExtractionSession session = service.begin(PLAYER_ID, GAME_ID, POINT_ID)
                    .orElseThrow(IllegalStateException::new);
            scheduler.advanceBy(POINT_DURATION);

            assertFalse(service.completeSettlement(PLAYER_ID, RaidGameId.of("other"), POINT_ID));
            assertEquals(ExtractionState.SETTLING, session.state());
            assertTrue(service.completeSettlement(PLAYER_ID, GAME_ID, POINT_ID));
            assertEquals(ExtractionState.COMPLETED, session.state());
            assertFalse(service.completeSettlement(PLAYER_ID, GAME_ID, POINT_ID));
            assertEquals(0, service.activeCount());
        }

        @Test
        void terminalSettlementConflictFailsAndRemovesTheSession() {
            ExtractionSession session = service.begin(PLAYER_ID, GAME_ID, POINT_ID)
                    .orElseThrow(IllegalStateException::new);
            scheduler.advanceBy(POINT_DURATION);

            assertTrue(service.failSettlement(PLAYER_ID, GAME_ID, POINT_ID));
            assertEquals(ExtractionState.FAILED, session.state());
            assertEquals(Optional.empty(), service.activeSession(PLAYER_ID));
        }

        @Test
        @DisplayName("fails the session without publishing when the game vanished before the timer fired")
        void failsWhenGameDisappearedBeforeTimerFired() {
            ExtractionSession session = service.begin(PLAYER_ID, GAME_ID, POINT_ID)
                    .orElseThrow(IllegalStateException::new);
            games.destroyAll();

            scheduler.advanceBy(POINT_DURATION);

            assertEquals(ExtractionState.FAILED, session.state());
            assertEquals(Optional.empty(), service.activeSession(PLAYER_ID));
            assertEquals(0, service.activeCount());
            assertTrue(published.isEmpty());
        }

        @Test
        @DisplayName("fails the session without publishing when the game is no longer RUNNING")
        void failsWhenGameStoppedRunningBeforeTimerFired() {
            ExtractionSession session = service.begin(PLAYER_ID, GAME_ID, POINT_ID)
                    .orElseThrow(IllegalStateException::new);
            game.setState(RaidGameState.ENDING);

            scheduler.advanceBy(POINT_DURATION);

            assertEquals(ExtractionState.FAILED, session.state());
            assertEquals(Optional.empty(), service.activeSession(PLAYER_ID));
            assertEquals(0, service.activeCount());
            assertTrue(published.isEmpty());
        }
    }

    @Nested
    @DisplayName("raid end handling")
    class RaidEndHandling {

        @Test
        @DisplayName("fails, cancels and removes every active session of the ended game")
        void cancelsAllSessionsOfEndedGame() {
            eventBus.register(service);
            game.addPlayer(new RaidPlayer(OTHER_PLAYER_ID, "Bob"));
            ExtractionSession first = service.begin(PLAYER_ID, GAME_ID, POINT_ID)
                    .orElseThrow(IllegalStateException::new);
            ExtractionSession second = service.begin(OTHER_PLAYER_ID, GAME_ID, POINT_ID)
                    .orElseThrow(IllegalStateException::new);

            eventBus.publish(endedEvent(GAME_ID));

            assertEquals(ExtractionState.FAILED, first.state());
            assertEquals(ExtractionState.FAILED, second.state());
            assertEquals(0, service.activeCount());

            scheduler.advanceBy(POINT_DURATION);
            assertTrue(published.isEmpty());
        }

        @Test
        void leavesSessionsOfOtherGamesUntouched() {
            eventBus.register(service);
            ExtractionSession session = service.begin(PLAYER_ID, GAME_ID, POINT_ID)
                    .orElseThrow(IllegalStateException::new);

            eventBus.publish(endedEvent(RaidGameId.of("raid-2")));

            assertEquals(ExtractionState.EXTRACTING, session.state());
            assertEquals(Optional.of(session), service.activeSession(PLAYER_ID));
            assertEquals(1, service.activeCount());
        }

        @Test
        void keepsEarnedSettlementPendingWhenRaidEnds() {
            eventBus.register(service);
            ExtractionSession session = service.begin(PLAYER_ID, GAME_ID, POINT_ID)
                    .orElseThrow(IllegalStateException::new);
            scheduler.advanceBy(POINT_DURATION);

            eventBus.publish(endedEvent(GAME_ID));

            assertEquals(ExtractionState.SETTLING, session.state());
            assertEquals(Optional.of(session), service.activeSession(PLAYER_ID));
            assertTrue(service.completeSettlement(PLAYER_ID, GAME_ID, POINT_ID));
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        void cancelFailsTheSessionAndPreventsCompletion() {
            ExtractionSession session = service.begin(PLAYER_ID, GAME_ID, POINT_ID)
                    .orElseThrow(IllegalStateException::new);

            assertTrue(service.cancel(PLAYER_ID));

            assertEquals(ExtractionState.FAILED, session.state());
            assertEquals(Optional.empty(), service.activeSession(PLAYER_ID));
            assertEquals(0, service.activeCount());

            scheduler.advanceBy(POINT_DURATION);

            assertEquals(ExtractionState.FAILED, session.state());
            assertTrue(published.isEmpty());
        }

        @Test
        void cancelReturnsFalseWithoutAnActiveExtraction() {
            assertFalse(service.cancel(PLAYER_ID));
        }

        @Test
        void cancelledPlayerCanBeginAgain() {
            assertTrue(service.begin(PLAYER_ID, GAME_ID, POINT_ID).isSuccess());
            assertTrue(service.cancel(PLAYER_ID));

            assertTrue(service.begin(PLAYER_ID, GAME_ID, POINT_ID).isSuccess());
            assertEquals(1, service.activeCount());
        }

        @Test
        void cannotCancelAfterTheTimerEarnedSettlement() {
            ExtractionSession session = service.begin(PLAYER_ID, GAME_ID, POINT_ID)
                    .orElseThrow(IllegalStateException::new);
            scheduler.advanceBy(POINT_DURATION);

            assertFalse(service.cancel(PLAYER_ID));
            assertEquals(ExtractionState.SETTLING, session.state());
            assertEquals(Optional.of(session), service.activeSession(PLAYER_ID));
        }

        @Test
        void throwsOffTheMainThread() {
            scheduler.setMainThread(false);
            assertThrows(IllegalStateException.class, () -> service.cancel(PLAYER_ID));
        }
    }

    private static ExtractionPoint point(ExtractionPointId id,
                                         List<ExtractionCondition> conditions,
                                         ExtractionState initialState) {
        return new ExtractionPoint(id, "Gate " + id.value(), ExtractionType.NORMAL,
                POINT_DURATION, conditions, initialState);
    }

    private static ExtractionCondition condition(String description, boolean satisfied) {
        return new ExtractionCondition() {
            @Override
            public String describe() {
                return description;
            }

            @Override
            public boolean isSatisfied(ExtractionContext context) {
                return satisfied;
            }
        };
    }

    private static RaidEndedEvent endedEvent(RaidGameId gameId) {
        return new RaidEndedEvent(gameId,
                new RaidResult(gameId, Instant.EPOCH, Set.of(), Set.of()));
    }

    private static void assertFailure(Result<ExtractionSession> result, String expectedMessage) {
        assertEquals(Optional.of(expectedMessage), result.error());
    }
}
