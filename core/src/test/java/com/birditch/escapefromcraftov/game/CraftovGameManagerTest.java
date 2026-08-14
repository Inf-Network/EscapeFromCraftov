package com.birditch.escapefromcraftov.game;

import com.birditch.escapefromcraftov.api.game.GameFailures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.event.EventSubscription;
import com.birditch.escapefromcraftov.api.event.extraction.PlayerExtractedEvent;
import com.birditch.escapefromcraftov.api.event.game.PlayerJoinedCraftovEvent;
import com.birditch.escapefromcraftov.api.event.game.PlayerLeftCraftovEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovCreatedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovStartedEvent;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.extraction.ExtractionState;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovResult;
import com.birditch.escapefromcraftov.api.testing.ManualScheduler;
import com.birditch.escapefromcraftov.api.testing.MutableTimeSource;
import com.birditch.escapefromcraftov.common.random.SeededRandomProvider;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.core.config.EscapeFromCraftovConfig;
import com.birditch.escapefromcraftov.core.event.SimpleEventBus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CraftovGameManager")
class CraftovGameManagerTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID DAVE = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID EVE = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final CraftovMapId MAP_ID = CraftovMapId.of("debug_zone");
    private static final ExtractionPointId NORTH_GATE = ExtractionPointId.of("north_gate");
    private static final Duration GAME_DURATION = Duration.ofSeconds(60);
    private static final int MAX_INSTANCES = 3;
    private static final Logger LOGGER = Logger.getLogger(CraftovGameManagerTest.class.getName());

    private final ManualScheduler scheduler = new ManualScheduler();
    private final MutableTimeSource timeSource = new MutableTimeSource(0L);
    private final SimpleEventBus eventBus = new SimpleEventBus(LOGGER);
    private final FakeSessionService sessions = new FakeSessionService();
    private final CraftovGameManager manager = new CraftovGameManager(new GameManagerDeps(
            sessions, eventBus, scheduler, new SeededRandomProvider(42L), timeSource,
            new EscapeFromCraftovConfig.GameSettings(4, 2, (int) GAME_DURATION.toSeconds(), MAX_INSTANCES),
            new EscapeFromCraftovConfig.ExtractionSettings(10), LOGGER));
    private final RecordedGameEvents events = new RecordedGameEvents(eventBus);

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("creates a WAITING game with the default extraction point")
        void createsWaitingGame() {
            timeSource.set(5_000L);

            CraftovGame game = createGame();

            assertEquals(CraftovGameState.WAITING, game.state());
            assertEquals(MAP_ID, game.mapId());
            assertEquals(Instant.ofEpochMilli(5_000L), game.createdAt());
            ExtractionPoint point = game.extractionPoints().getFirst();
            assertEquals(NORTH_GATE, point.id());
            assertEquals("北门", point.displayName());
            assertEquals(Duration.ofSeconds(10), point.duration());
            assertEquals(ExtractionState.AVAILABLE, point.state());
            assertEquals(1, manager.activeCount());
            assertEquals(Optional.of(game), manager.find(game.id()));
            assertEquals(List.of(new CraftovCreatedEvent(game.id(), MAP_ID)), events.created);
        }

        @Test
        void generatesUniqueSixCharacterHexIds() {
            CraftovGame first = createGame();
            CraftovGame second = createGame();

            assertTrue(first.id().value().matches("[0-9a-f]{6}"));
            assertTrue(second.id().value().matches("[0-9a-f]{6}"));
            assertEquals(2, manager.activeCount());
        }

        @Test
        @DisplayName("fails at the instance cap and accepts again once a game is destroyed")
        void failsAtInstanceCap() {
            CraftovGame first = createGame();
            createGame();
            createGame();

            assertFailure(manager.create(MAP_ID), GameFailures.instanceLimitReached(3));
            assertEquals(MAX_INSTANCES, manager.activeCount());
            assertEquals(MAX_INSTANCES, events.created.size());

            assertTrue(manager.stop(first.id()).isSuccess());
            assertTrue(manager.create(MAP_ID).isSuccess());
        }
    }

    @Nested
    @DisplayName("join")
    class Join {

        @Test
        @DisplayName("adds the player with a team and binds the session")
        void addsPlayerWithTeamAndSession() {
            CraftovGame game = createGame();
            sessions.create(ALICE, "Alice");

            Result<CraftovGame> result = manager.join(game.id(), ALICE);

            assertTrue(result.isSuccess());
            CraftovPlayer alice = playerIn(game, ALICE);
            assertEquals("Alice", alice.name());
            assertEquals(OptionalInt.of(1), alice.teamId());
            assertEquals(Optional.of(game.id()),
                    sessions.find(ALICE).orElseThrow().currentGame());
            assertEquals(List.of(new PlayerJoinedCraftovEvent(game.id(), ALICE)), events.joined);
        }

        @Test
        void failsWithoutSession() {
            CraftovGame game = createGame();

            assertFailure(manager.join(game.id(), ALICE), GameFailures.NO_SESSION);
        }

        @Test
        void failsUntilProfileIsReady() {
            CraftovGame game = createGame();
            sessions.createLoading(ALICE, "Alice");

            assertFailure(manager.join(game.id(), ALICE), GameFailures.PROFILE_NOT_READY);
        }

        @Test
        void failsWhenAlreadyInAGame() {
            CraftovGame first = createGame();
            CraftovGame second = createGame();
            joinWithSession(first.id(), ALICE, "Alice");

            assertFailure(manager.join(second.id(), ALICE), GameFailures.ALREADY_IN_GAME);
        }

        @Test
        void failsWhenGameNotFound() {
            sessions.create(ALICE, "Alice");

            assertFailure(manager.join(CraftovGameId.of("missing"), ALICE), GameFailures.GAME_NOT_FOUND);
        }

        @Test
        void failsWhenGameIsNotAcceptingPlayers() {
            CraftovGame game = createGame();
            assertTrue(manager.start(game.id()).isSuccess());
            sessions.create(ALICE, "Alice");

            assertFailure(manager.join(game.id(), ALICE), GameFailures.NOT_ACCEPTING);
        }

        @Test
        @DisplayName("fills teams of team-size before creating new ones, rejects a 5th player")
        void failsWhenGameIsFull() {
            CraftovGame game = createGame();
            joinWithSession(game.id(), ALICE, "Alice");
            joinWithSession(game.id(), BOB, "Bob");
            joinWithSession(game.id(), CARA, "Cara");
            joinWithSession(game.id(), DAVE, "Dave");
            sessions.create(EVE, "Eve");

            assertFailure(manager.join(game.id(), EVE), GameFailures.GAME_FULL);
            assertEquals(4, game.playerCount());
            assertEquals(OptionalInt.of(1), playerIn(game, BOB).teamId());
            assertEquals(OptionalInt.of(2), playerIn(game, CARA).teamId());
            assertEquals(2, game.teams().size());
        }
    }

    @Nested
    @DisplayName("leave")
    class Leave {

        @Test
        void removesPlayerAndClearsSession() {
            CraftovGame game = createGame();
            joinWithSession(game.id(), ALICE, "Alice");

            Result<CraftovGame> result = manager.leave(ALICE);

            assertTrue(result.isSuccess());
            assertEquals(0, game.playerCount());
            assertEquals(Optional.empty(), sessions.find(ALICE).orElseThrow().currentGame());
            assertEquals(List.of(new PlayerLeftCraftovEvent(game.id(), ALICE)), events.left);
        }

        @Test
        void failsWhenNotInAGame() {
            assertFailure(manager.leave(ALICE), GameFailures.NOT_IN_GAME);
            sessions.create(ALICE, "Alice");
            assertFailure(manager.leave(ALICE), GameFailures.NOT_IN_GAME);
        }
    }

    @Nested
    @DisplayName("start")
    class Start {

        @Test
        @DisplayName("moves the game to RUNNING and records the start time")
        void movesGameToRunning() {
            CraftovGame game = createGame();
            timeSource.set(7_000L);

            Result<CraftovGame> result = manager.start(game.id());

            assertTrue(result.isSuccess());
            assertEquals(CraftovGameState.RUNNING, game.state());
            assertEquals(Optional.of(Instant.ofEpochMilli(7_000L)), game.startedAt());
            assertEquals(List.of(new CraftovStartedEvent(game.id())), events.started);
        }

        @Test
        void failsWhenGameNotFound() {
            assertFailure(manager.start(CraftovGameId.of("missing")), GameFailures.GAME_NOT_FOUND);
        }

        @Test
        void failsWhenNotWaiting() {
            CraftovGame game = createGame();
            assertTrue(manager.start(game.id()).isSuccess());

            assertFailure(manager.start(game.id()), GameFailures.cannotStartFrom(CraftovGameState.RUNNING));
        }

        @Test
        @DisplayName("auto-stops the game once the configured duration elapses")
        void autoStopsAfterConfiguredDuration() {
            CraftovGame game = createGame();
            joinWithSession(game.id(), ALICE, "Alice");
            assertTrue(manager.start(game.id()).isSuccess());

            scheduler.advanceBy(GAME_DURATION.minusMillis(1));
            assertEquals(CraftovGameState.RUNNING, game.state());
            assertTrue(events.ended.isEmpty());

            scheduler.advanceBy(Duration.ofMillis(1));

            assertEquals(0, manager.activeCount());
            assertEquals(Optional.empty(), manager.find(game.id()));
            assertEquals(Optional.empty(), sessions.find(ALICE).orElseThrow().currentGame());
            assertEquals(1, events.ended.size());
        }
    }

    @Nested
    @DisplayName("stop")
    class Stop {

        @Test
        @DisplayName("finishes, publishes the result and destroys the game")
        void finishesAndDestroysGame() {
            CraftovGame game = createGame();
            joinWithSession(game.id(), ALICE, "Alice");
            joinWithSession(game.id(), BOB, "Bob");
            assertTrue(manager.start(game.id()).isSuccess());
            timeSource.set(9_000L);

            Result<CraftovGame> result = manager.stop(game.id());

            assertTrue(result.isSuccess());
            assertEquals(CraftovGameState.DESTROYED, game.state());
            assertEquals(0, manager.activeCount());
            assertEquals(Optional.empty(), manager.find(game.id()));
            assertEquals(Optional.empty(), sessions.find(ALICE).orElseThrow().currentGame());
            assertEquals(Optional.empty(), sessions.find(BOB).orElseThrow().currentGame());
            CraftovResult craftovResult = game.result().orElseThrow();
            assertEquals(Instant.ofEpochMilli(9_000L), craftovResult.endedAt());
            assertEquals(Set.of(), craftovResult.extractedPlayers());
            assertEquals(Set.of(ALICE, BOB), craftovResult.remainingPlayers());
            assertEquals(List.of(new CraftovEndedEvent(game.id(), craftovResult)), events.ended);
        }

        @Test
        void cancelsTheScheduledEndTask() {
            CraftovGame game = createGame();
            assertTrue(manager.start(game.id()).isSuccess());
            assertTrue(manager.stop(game.id()).isSuccess());

            scheduler.advanceBy(GAME_DURATION);

            assertEquals(1, events.ended.size());
        }

        @Test
        void failsWhenGameNotFound() {
            assertFailure(manager.stop(CraftovGameId.of("missing")), GameFailures.GAME_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("player extraction handling")
    class PlayerExtractionHandling {

        @Test
        @DisplayName("removes the extracted player but keeps the extraction record")
        void removesExtractedPlayer() {
            eventBus.register(manager);
            CraftovGame game = createGame();
            joinWithSession(game.id(), ALICE, "Alice");
            joinWithSession(game.id(), BOB, "Bob");
            assertTrue(manager.start(game.id()).isSuccess());

            eventBus.publish(new PlayerExtractedEvent(CraftovGameId.of("missing"), ALICE,
                    NORTH_GATE));
            assertEquals(2, game.playerCount());

            eventBus.publish(new PlayerExtractedEvent(game.id(), ALICE, NORTH_GATE));

            assertEquals(1, game.playerCount());
            assertEquals(Optional.empty(), sessions.find(ALICE).orElseThrow().currentGame());

            assertTrue(manager.stop(game.id()).isSuccess());
            CraftovResult result = game.result().orElseThrow();
            assertEquals(Set.of(ALICE), result.extractedPlayers());
            assertEquals(Set.of(BOB), result.remainingPlayers());
        }

        @Test
        void unsubscribingStopsHandling() {
            EventSubscription subscription = eventBus.register(manager);
            CraftovGame game = createGame();
            joinWithSession(game.id(), ALICE, "Alice");
            subscription.unsubscribe();

            eventBus.publish(new PlayerExtractedEvent(game.id(), ALICE, NORTH_GATE));

            assertEquals(1, game.playerCount());
        }
    }

    @Test
    void destroyAllDestroysEveryGameWithoutEvents() {
        CraftovGame first = createGame();
        createGame();
        joinWithSession(first.id(), ALICE, "Alice");
        assertTrue(manager.start(first.id()).isSuccess());

        manager.destroyAll();

        assertEquals(0, manager.activeCount());
        assertEquals(Optional.empty(), sessions.find(ALICE).orElseThrow().currentGame());
        assertTrue(events.ended.isEmpty());

        scheduler.advanceBy(GAME_DURATION);
        assertTrue(events.ended.isEmpty());
    }

    @Test
    void mutatorsThrowOffTheMainThread() {
        CraftovGame game = createGame();
        scheduler.setMainThread(false);

        assertThrows(IllegalStateException.class, () -> manager.create(MAP_ID));
        assertThrows(IllegalStateException.class, () -> manager.join(game.id(), ALICE));
        assertThrows(IllegalStateException.class, () -> manager.leave(ALICE));
        assertThrows(IllegalStateException.class, () -> manager.start(game.id()));
        assertThrows(IllegalStateException.class, () -> manager.stop(game.id()));
        assertThrows(IllegalStateException.class, () -> manager.destroyAll());
    }

    private CraftovGame createGame() {
        return manager.create(MAP_ID).orElseThrow(IllegalStateException::new);
    }

    private void joinWithSession(CraftovGameId gameId, UUID playerId, String name) {
        sessions.create(playerId, name);
        assertTrue(manager.join(gameId, playerId).isSuccess());
    }

    private static CraftovPlayer playerIn(CraftovGame game, UUID playerId) {
        return game.players().stream()
                .filter(player -> player.playerId().equals(playerId))
                .findFirst()
                .orElseThrow();
    }

    private static void assertFailure(Result<CraftovGame> result, String expectedMessage) {
        assertEquals(Optional.of(expectedMessage), result.error());
    }
}
