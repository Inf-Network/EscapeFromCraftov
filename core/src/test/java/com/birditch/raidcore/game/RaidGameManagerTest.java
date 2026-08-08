package com.birditch.raidcore.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.event.EventSubscription;
import com.birditch.raidcore.api.event.extraction.PlayerExtractedEvent;
import com.birditch.raidcore.api.event.game.PlayerJoinedRaidEvent;
import com.birditch.raidcore.api.event.game.PlayerLeftRaidEvent;
import com.birditch.raidcore.api.event.game.RaidCreatedEvent;
import com.birditch.raidcore.api.event.game.RaidEndedEvent;
import com.birditch.raidcore.api.event.game.RaidStartedEvent;
import com.birditch.raidcore.api.extraction.ExtractionPoint;
import com.birditch.raidcore.api.extraction.ExtractionPointId;
import com.birditch.raidcore.api.extraction.ExtractionState;
import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.game.RaidPlayer;
import com.birditch.raidcore.api.game.RaidResult;
import com.birditch.raidcore.api.testing.ManualScheduler;
import com.birditch.raidcore.api.testing.MutableTimeSource;
import com.birditch.raidcore.common.random.SeededRandomProvider;
import com.birditch.raidcore.common.result.Result;
import com.birditch.raidcore.core.config.RaidCoreConfig;
import com.birditch.raidcore.core.event.SimpleEventBus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RaidGameManager")
class RaidGameManagerTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID DAVE = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID EVE = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final RaidMapId MAP_ID = RaidMapId.of("debug_zone");
    private static final ExtractionPointId NORTH_GATE = ExtractionPointId.of("north_gate");
    private static final Duration GAME_DURATION = Duration.ofSeconds(60);
    private static final Logger LOGGER = Logger.getLogger(RaidGameManagerTest.class.getName());

    private final ManualScheduler scheduler = new ManualScheduler();
    private final MutableTimeSource timeSource = new MutableTimeSource(0L);
    private final SimpleEventBus eventBus = new SimpleEventBus(LOGGER);
    private final FakeSessionService sessions = new FakeSessionService();
    private final RaidGameManager manager = new RaidGameManager(new GameManagerDeps(
            sessions, eventBus, scheduler, new SeededRandomProvider(42L), timeSource,
            new RaidCoreConfig.GameSettings(4, 2, (int) GAME_DURATION.toSeconds()),
            new RaidCoreConfig.ExtractionSettings(10), LOGGER));
    private final List<RaidCreatedEvent> createdEvents = new ArrayList<>();
    private final List<PlayerJoinedRaidEvent> joinedEvents = new ArrayList<>();
    private final List<PlayerLeftRaidEvent> leftEvents = new ArrayList<>();
    private final List<RaidStartedEvent> startedEvents = new ArrayList<>();
    private final List<RaidEndedEvent> endedEvents = new ArrayList<>();

    @BeforeEach
    void subscribeEventRecorders() {
        eventBus.subscribe(RaidCreatedEvent.class, createdEvents::add);
        eventBus.subscribe(PlayerJoinedRaidEvent.class, joinedEvents::add);
        eventBus.subscribe(PlayerLeftRaidEvent.class, leftEvents::add);
        eventBus.subscribe(RaidStartedEvent.class, startedEvents::add);
        eventBus.subscribe(RaidEndedEvent.class, endedEvents::add);
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("creates a WAITING game with the default extraction point")
        void createsWaitingGame() {
            timeSource.set(5_000L);

            RaidGame game = manager.create(MAP_ID);

            assertEquals(RaidGameState.WAITING, game.state());
            assertEquals(MAP_ID, game.mapId());
            assertEquals(Instant.ofEpochMilli(5_000L), game.createdAt());
            ExtractionPoint point = game.extractionPoints().getFirst();
            assertEquals(NORTH_GATE, point.id());
            assertEquals("North Gate", point.displayName());
            assertEquals(Duration.ofSeconds(10), point.duration());
            assertEquals(ExtractionState.AVAILABLE, point.state());
            assertEquals(1, manager.activeCount());
            assertEquals(Optional.of(game), manager.find(game.id()));
            assertEquals(List.of(new RaidCreatedEvent(game.id(), MAP_ID)), createdEvents);
        }

        @Test
        void generatesUniqueSixCharacterHexIds() {
            RaidGame first = manager.create(MAP_ID);
            RaidGame second = manager.create(MAP_ID);

            assertTrue(first.id().value().matches("[0-9a-f]{6}"));
            assertTrue(second.id().value().matches("[0-9a-f]{6}"));
            assertEquals(2, manager.activeCount());
        }
    }

    @Nested
    @DisplayName("join")
    class Join {

        @Test
        @DisplayName("adds the player with a team and binds the session")
        void addsPlayerWithTeamAndSession() {
            RaidGame game = manager.create(MAP_ID);
            sessions.create(ALICE, "Alice");

            Result<RaidGame> result = manager.join(game.id(), ALICE);

            assertTrue(result.isSuccess());
            RaidPlayer alice = playerIn(game, ALICE);
            assertEquals("Alice", alice.name());
            assertEquals(OptionalInt.of(1), alice.teamId());
            assertEquals(Optional.of(game.id()),
                    sessions.find(ALICE).orElseThrow().currentGame());
            assertEquals(List.of(new PlayerJoinedRaidEvent(game.id(), ALICE)), joinedEvents);
        }

        @Test
        void failsWithoutSession() {
            RaidGame game = manager.create(MAP_ID);

            assertFailure(manager.join(game.id(), ALICE), "Player has no session");
        }

        @Test
        void failsWhenAlreadyInAGame() {
            RaidGame first = manager.create(MAP_ID);
            RaidGame second = manager.create(MAP_ID);
            joinWithSession(first.id(), ALICE, "Alice");

            assertFailure(manager.join(second.id(), ALICE), "Player is already in a game");
        }

        @Test
        void failsWhenGameNotFound() {
            sessions.create(ALICE, "Alice");

            assertFailure(manager.join(RaidGameId.of("missing"), ALICE), "Game not found");
        }

        @Test
        void failsWhenGameIsNotAcceptingPlayers() {
            RaidGame game = manager.create(MAP_ID);
            assertTrue(manager.start(game.id()).isSuccess());
            sessions.create(ALICE, "Alice");

            assertFailure(manager.join(game.id(), ALICE), "Game is not accepting players");
        }

        @Test
        @DisplayName("fills teams of team-size before creating new ones, rejects a 5th player")
        void failsWhenGameIsFull() {
            RaidGame game = manager.create(MAP_ID);
            joinWithSession(game.id(), ALICE, "Alice");
            joinWithSession(game.id(), BOB, "Bob");
            joinWithSession(game.id(), CARA, "Cara");
            joinWithSession(game.id(), DAVE, "Dave");
            sessions.create(EVE, "Eve");

            assertFailure(manager.join(game.id(), EVE), "Game is full");
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
            RaidGame game = manager.create(MAP_ID);
            joinWithSession(game.id(), ALICE, "Alice");

            Result<RaidGame> result = manager.leave(ALICE);

            assertTrue(result.isSuccess());
            assertEquals(0, game.playerCount());
            assertEquals(Optional.empty(), sessions.find(ALICE).orElseThrow().currentGame());
            assertEquals(List.of(new PlayerLeftRaidEvent(game.id(), ALICE)), leftEvents);
        }

        @Test
        void failsWhenNotInAGame() {
            assertFailure(manager.leave(ALICE), "Player is not in a game");
            sessions.create(ALICE, "Alice");
            assertFailure(manager.leave(ALICE), "Player is not in a game");
        }
    }

    @Nested
    @DisplayName("start")
    class Start {

        @Test
        @DisplayName("moves the game to RUNNING and records the start time")
        void movesGameToRunning() {
            RaidGame game = manager.create(MAP_ID);
            timeSource.set(7_000L);

            Result<RaidGame> result = manager.start(game.id());

            assertTrue(result.isSuccess());
            assertEquals(RaidGameState.RUNNING, game.state());
            assertEquals(Optional.of(Instant.ofEpochMilli(7_000L)), game.startedAt());
            assertEquals(List.of(new RaidStartedEvent(game.id())), startedEvents);
        }

        @Test
        void failsWhenGameNotFound() {
            assertFailure(manager.start(RaidGameId.of("missing")), "Game not found");
        }

        @Test
        void failsWhenNotWaiting() {
            RaidGame game = manager.create(MAP_ID);
            assertTrue(manager.start(game.id()).isSuccess());

            assertFailure(manager.start(game.id()), "Game cannot start from state RUNNING");
        }

        @Test
        @DisplayName("auto-stops the game once the configured duration elapses")
        void autoStopsAfterConfiguredDuration() {
            RaidGame game = manager.create(MAP_ID);
            joinWithSession(game.id(), ALICE, "Alice");
            assertTrue(manager.start(game.id()).isSuccess());

            scheduler.advanceBy(GAME_DURATION.minusMillis(1));
            assertEquals(RaidGameState.RUNNING, game.state());
            assertTrue(endedEvents.isEmpty());

            scheduler.advanceBy(Duration.ofMillis(1));

            assertEquals(0, manager.activeCount());
            assertEquals(Optional.empty(), manager.find(game.id()));
            assertEquals(Optional.empty(), sessions.find(ALICE).orElseThrow().currentGame());
            assertEquals(1, endedEvents.size());
        }
    }

    @Nested
    @DisplayName("stop")
    class Stop {

        @Test
        @DisplayName("finishes, publishes the result and destroys the game")
        void finishesAndDestroysGame() {
            RaidGame game = manager.create(MAP_ID);
            joinWithSession(game.id(), ALICE, "Alice");
            joinWithSession(game.id(), BOB, "Bob");
            assertTrue(manager.start(game.id()).isSuccess());
            timeSource.set(9_000L);

            Result<RaidGame> result = manager.stop(game.id());

            assertTrue(result.isSuccess());
            assertEquals(RaidGameState.DESTROYED, game.state());
            assertEquals(0, manager.activeCount());
            assertEquals(Optional.empty(), manager.find(game.id()));
            assertEquals(Optional.empty(), sessions.find(ALICE).orElseThrow().currentGame());
            assertEquals(Optional.empty(), sessions.find(BOB).orElseThrow().currentGame());
            RaidResult raidResult = game.result().orElseThrow();
            assertEquals(Instant.ofEpochMilli(9_000L), raidResult.endedAt());
            assertEquals(Set.of(), raidResult.extractedPlayers());
            assertEquals(Set.of(ALICE, BOB), raidResult.remainingPlayers());
            assertEquals(List.of(new RaidEndedEvent(game.id(), raidResult)), endedEvents);
        }

        @Test
        void cancelsTheScheduledEndTask() {
            RaidGame game = manager.create(MAP_ID);
            assertTrue(manager.start(game.id()).isSuccess());
            assertTrue(manager.stop(game.id()).isSuccess());

            scheduler.advanceBy(GAME_DURATION);

            assertEquals(1, endedEvents.size());
        }

        @Test
        void failsWhenGameNotFound() {
            assertFailure(manager.stop(RaidGameId.of("missing")), "Game not found");
        }
    }

    @Nested
    @DisplayName("player extraction handling")
    class PlayerExtractionHandling {

        @Test
        @DisplayName("removes the extracted player but keeps the extraction record")
        void removesExtractedPlayer() {
            eventBus.register(manager);
            RaidGame game = manager.create(MAP_ID);
            joinWithSession(game.id(), ALICE, "Alice");
            joinWithSession(game.id(), BOB, "Bob");
            assertTrue(manager.start(game.id()).isSuccess());

            eventBus.publish(new PlayerExtractedEvent(RaidGameId.of("missing"), ALICE,
                    NORTH_GATE));
            assertEquals(2, game.playerCount());

            eventBus.publish(new PlayerExtractedEvent(game.id(), ALICE, NORTH_GATE));

            assertEquals(1, game.playerCount());
            assertEquals(Optional.empty(), sessions.find(ALICE).orElseThrow().currentGame());

            assertTrue(manager.stop(game.id()).isSuccess());
            RaidResult result = game.result().orElseThrow();
            assertEquals(Set.of(ALICE), result.extractedPlayers());
            assertEquals(Set.of(BOB), result.remainingPlayers());
        }

        @Test
        void unsubscribingStopsHandling() {
            EventSubscription subscription = eventBus.register(manager);
            RaidGame game = manager.create(MAP_ID);
            joinWithSession(game.id(), ALICE, "Alice");
            subscription.unsubscribe();

            eventBus.publish(new PlayerExtractedEvent(game.id(), ALICE, NORTH_GATE));

            assertEquals(1, game.playerCount());
        }
    }

    @Test
    void destroyAllDestroysEveryGameWithoutEvents() {
        RaidGame first = manager.create(MAP_ID);
        manager.create(MAP_ID);
        joinWithSession(first.id(), ALICE, "Alice");
        assertTrue(manager.start(first.id()).isSuccess());

        manager.destroyAll();

        assertEquals(0, manager.activeCount());
        assertEquals(Optional.empty(), sessions.find(ALICE).orElseThrow().currentGame());
        assertTrue(endedEvents.isEmpty());

        scheduler.advanceBy(GAME_DURATION);
        assertTrue(endedEvents.isEmpty());
    }

    @Test
    void mutatorsThrowOffTheMainThread() {
        RaidGame game = manager.create(MAP_ID);
        scheduler.setMainThread(false);

        assertThrows(IllegalStateException.class, () -> manager.create(MAP_ID));
        assertThrows(IllegalStateException.class, () -> manager.join(game.id(), ALICE));
        assertThrows(IllegalStateException.class, () -> manager.leave(ALICE));
        assertThrows(IllegalStateException.class, () -> manager.start(game.id()));
        assertThrows(IllegalStateException.class, () -> manager.stop(game.id()));
        assertThrows(IllegalStateException.class, () -> manager.destroyAll());
    }

    private void joinWithSession(RaidGameId gameId, UUID playerId, String name) {
        sessions.create(playerId, name);
        assertTrue(manager.join(gameId, playerId).isSuccess());
    }

    private static RaidPlayer playerIn(RaidGame game, UUID playerId) {
        return game.players().stream()
                .filter(player -> player.playerId().equals(playerId))
                .findFirst()
                .orElseThrow();
    }

    private static void assertFailure(Result<RaidGame> result, String expectedMessage) {
        assertEquals(Optional.of(expectedMessage), result.error());
    }
}
