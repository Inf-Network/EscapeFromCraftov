package com.birditch.raidcore.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.RaidException;
import com.birditch.raidcore.api.extraction.ExtractionPoint;
import com.birditch.raidcore.api.extraction.ExtractionPointId;
import com.birditch.raidcore.api.extraction.ExtractionState;
import com.birditch.raidcore.api.extraction.ExtractionType;
import com.birditch.raidcore.api.game.GameStateException;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.game.RaidPlayer;
import com.birditch.raidcore.api.game.RaidResult;
import com.birditch.raidcore.api.game.RaidTeam;
import com.birditch.raidcore.api.scheduler.ScheduledTask;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RaidGameInstance")
class RaidGameInstanceTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final RaidGameId GAME_ID = RaidGameId.of("game-1");
    private static final RaidMapId MAP_ID = RaidMapId.of("debug_zone");
    private static final Instant CREATED_AT = Instant.EPOCH;

    private RaidGameInstance game;

    @BeforeEach
    void setUp() {
        game = new RaidGameInstance(GAME_ID, MAP_ID, CREATED_AT, List.of(northGate()));
    }

    @Test
    @DisplayName("starts CREATED and exposes its construction values")
    void startsCreated() {
        assertEquals(RaidGameState.CREATED, game.state());
        assertEquals(GAME_ID, game.id());
        assertEquals(MAP_ID, game.mapId());
        assertEquals(CREATED_AT, game.createdAt());
        assertEquals(1, game.extractionPoints().size());
        assertEquals(Optional.empty(), game.startedAt());
        assertEquals(Optional.empty(), game.result());
        assertEquals(0, game.playerCount());
    }

    @Test
    @DisplayName("copies the extraction point list defensively")
    void copiesExtractionPoints() {
        List<ExtractionPoint> points = new ArrayList<>(List.of(northGate()));
        RaidGameInstance copied = new RaidGameInstance(GAME_ID, MAP_ID, CREATED_AT, points);
        points.clear();

        assertEquals(1, copied.extractionPoints().size());
    }

    @Nested
    @DisplayName("transitionTo")
    class TransitionTo {

        @Test
        void movesToTheNextLegalState() {
            game.transitionTo(RaidGameState.WAITING, CREATED_AT);

            assertEquals(RaidGameState.WAITING, game.state());
        }

        @Test
        void rejectsAnIllegalTransition() {
            assertThrows(GameStateException.class,
                    () -> game.transitionTo(RaidGameState.RUNNING, CREATED_AT));
            assertEquals(RaidGameState.CREATED, game.state());
        }

        @Test
        void recordsStartTimeWhenEnteringRunning() {
            Instant startInstant = Instant.ofEpochMilli(5_000L);
            game.transitionTo(RaidGameState.WAITING, CREATED_AT);
            game.transitionTo(RaidGameState.STARTING, CREATED_AT);
            assertEquals(Optional.empty(), game.startedAt());

            game.transitionTo(RaidGameState.RUNNING, startInstant);

            assertEquals(Optional.of(startInstant), game.startedAt());
        }
    }

    @Nested
    @DisplayName("players")
    class Players {

        @Test
        void addPlayerWorksWhileWaiting() {
            game.transitionTo(RaidGameState.WAITING, CREATED_AT);
            RaidPlayer alice = new RaidPlayer(ALICE, "Alice");

            game.addPlayer(alice);

            assertEquals(1, game.playerCount());
            assertEquals(Optional.of(alice), game.player(ALICE));
            assertTrue(game.players().contains(alice));
        }

        @Test
        void addPlayerRejectsOtherStates() {
            assertThrows(RaidException.class, () -> game.addPlayer(new RaidPlayer(ALICE, "Alice")));
            assertEquals(0, game.playerCount());
        }

        @Test
        void addPlayerRejectsDuplicates() {
            game.transitionTo(RaidGameState.WAITING, CREATED_AT);
            game.addPlayer(new RaidPlayer(ALICE, "Alice"));

            assertThrows(IllegalArgumentException.class,
                    () -> game.addPlayer(new RaidPlayer(ALICE, "Alice")));
            assertEquals(1, game.playerCount());
        }

        @Test
        void removePlayerReturnsTheRemovedPlayer() {
            game.transitionTo(RaidGameState.WAITING, CREATED_AT);
            RaidPlayer alice = new RaidPlayer(ALICE, "Alice");
            game.addPlayer(alice);

            assertEquals(Optional.of(alice), game.removePlayer(ALICE));
            assertEquals(0, game.playerCount());
            assertEquals(Optional.empty(), game.player(ALICE));
        }

        @Test
        void removePlayerReturnsEmptyForUnknownPlayer() {
            assertEquals(Optional.empty(), game.removePlayer(ALICE));
        }

        @Test
        void removePlayerAlsoRemovesTeamMembership() {
            game.transitionTo(RaidGameState.WAITING, CREATED_AT);
            game.addPlayer(new RaidPlayer(ALICE, "Alice"));
            RaidTeam team = game.teamWithSpaceOrCreate(2);
            assertTrue(team.addMember(ALICE));

            game.removePlayer(ALICE);

            assertEquals(0, team.size());
        }
    }

    @Nested
    @DisplayName("extraction records")
    class ExtractionRecords {

        @Test
        void markExtractedFlagsThePlayer() {
            game.transitionTo(RaidGameState.WAITING, CREATED_AT);
            RaidPlayer alice = new RaidPlayer(ALICE, "Alice");
            game.addPlayer(alice);

            game.markExtracted(ALICE);

            assertTrue(alice.extracted());
            assertEquals(Set.of(ALICE), game.extractedPlayerIds());
        }

        @Test
        void extractionRecordSurvivesPlayerRemoval() {
            game.transitionTo(RaidGameState.WAITING, CREATED_AT);
            game.addPlayer(new RaidPlayer(ALICE, "Alice"));
            game.markExtracted(ALICE);

            game.removePlayer(ALICE);

            assertEquals(Set.of(ALICE), game.extractedPlayerIds());
        }

        @Test
        void extractedPlayerIdsIsUnmodifiable() {
            assertThrows(UnsupportedOperationException.class,
                    () -> game.extractedPlayerIds().add(BOB));
        }
    }

    @Nested
    @DisplayName("teamWithSpaceOrCreate")
    class TeamWithSpaceOrCreate {

        @Test
        void assignsTeamIdsFromOne() {
            RaidTeam first = game.teamWithSpaceOrCreate(1);
            assertTrue(first.addMember(ALICE));

            RaidTeam second = game.teamWithSpaceOrCreate(1);

            assertEquals(1, first.teamId());
            assertEquals(2, second.teamId());
            assertEquals(2, game.teams().size());
        }

        @Test
        void reusesTheFirstTeamWithFreeCapacity() {
            RaidTeam first = game.teamWithSpaceOrCreate(2);
            assertTrue(first.addMember(ALICE));

            assertSame(first, game.teamWithSpaceOrCreate(2));
            assertEquals(1, game.teams().size());
        }
    }

    @Nested
    @DisplayName("end task and result")
    class EndTaskAndResult {

        @Test
        void endTaskRoundTrip() {
            assertEquals(Optional.empty(), game.endTask());
            RecordingTask task = new RecordingTask();

            game.setEndTask(task);

            assertEquals(Optional.of(task), game.endTask());
        }

        @Test
        void cancelEndTaskCancelsAndClears() {
            RecordingTask task = new RecordingTask();
            game.setEndTask(task);

            game.cancelEndTask();

            assertTrue(task.cancelled());
            assertEquals(Optional.empty(), game.endTask());
        }

        @Test
        void cancelEndTaskWithoutTaskIsANoOp() {
            game.cancelEndTask();

            assertEquals(Optional.empty(), game.endTask());
        }

        @Test
        void resultRoundTrip() {
            RaidResult result = new RaidResult(GAME_ID, CREATED_AT, Set.of(ALICE), Set.of(BOB));
            assertEquals(Optional.empty(), game.result());

            game.setResult(result);

            assertEquals(Optional.of(result), game.result());
        }
    }

    private static ExtractionPoint northGate() {
        return new ExtractionPoint(ExtractionPointId.of("north_gate"), "North Gate",
                ExtractionType.NORMAL, Duration.ofSeconds(10), List.of(),
                ExtractionState.AVAILABLE);
    }

    private static final class RecordingTask implements ScheduledTask {

        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }
    }
}
