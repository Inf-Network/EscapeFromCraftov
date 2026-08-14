package com.birditch.escapefromcraftov.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.CraftovException;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.extraction.ExtractionState;
import com.birditch.escapefromcraftov.api.extraction.ExtractionType;
import com.birditch.escapefromcraftov.api.game.GameStateException;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovResult;
import com.birditch.escapefromcraftov.api.game.CraftovTeam;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
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

@DisplayName("CraftovGameInstance")
class CraftovGameInstanceTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final CraftovGameId GAME_ID = CraftovGameId.of("game-1");
    private static final CraftovMapId MAP_ID = CraftovMapId.of("debug_zone");
    private static final Instant CREATED_AT = Instant.EPOCH;

    private CraftovGameInstance game;

    @BeforeEach
    void setUp() {
        game = new CraftovGameInstance(GAME_ID, MAP_ID, CREATED_AT, List.of(northGate()));
    }

    @Test
    @DisplayName("starts CREATED and exposes its construction values")
    void startsCreated() {
        assertEquals(CraftovGameState.CREATED, game.state());
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
        CraftovGameInstance copied = new CraftovGameInstance(GAME_ID, MAP_ID, CREATED_AT, points);
        points.clear();

        assertEquals(1, copied.extractionPoints().size());
    }

    @Nested
    @DisplayName("transitionTo")
    class TransitionTo {

        @Test
        void movesToTheNextLegalState() {
            game.transitionTo(CraftovGameState.WAITING, CREATED_AT);

            assertEquals(CraftovGameState.WAITING, game.state());
        }

        @Test
        void rejectsAnIllegalTransition() {
            assertThrows(GameStateException.class,
                    () -> game.transitionTo(CraftovGameState.RUNNING, CREATED_AT));
            assertEquals(CraftovGameState.CREATED, game.state());
        }

        @Test
        void recordsStartTimeWhenEnteringRunning() {
            Instant startInstant = Instant.ofEpochMilli(5_000L);
            game.transitionTo(CraftovGameState.WAITING, CREATED_AT);
            game.transitionTo(CraftovGameState.STARTING, CREATED_AT);
            assertEquals(Optional.empty(), game.startedAt());

            game.transitionTo(CraftovGameState.RUNNING, startInstant);

            assertEquals(Optional.of(startInstant), game.startedAt());
        }
    }

    @Nested
    @DisplayName("players")
    class Players {

        @Test
        void addPlayerWorksWhileWaiting() {
            game.transitionTo(CraftovGameState.WAITING, CREATED_AT);
            CraftovPlayer alice = new CraftovPlayer(ALICE, "Alice");

            game.addPlayer(alice);

            assertEquals(1, game.playerCount());
            assertEquals(Optional.of(alice), game.player(ALICE));
            assertTrue(game.players().contains(alice));
        }

        @Test
        void addPlayerRejectsOtherStates() {
            assertThrows(CraftovException.class, () -> game.addPlayer(new CraftovPlayer(ALICE, "Alice")));
            assertEquals(0, game.playerCount());
        }

        @Test
        void addPlayerRejectsDuplicates() {
            game.transitionTo(CraftovGameState.WAITING, CREATED_AT);
            game.addPlayer(new CraftovPlayer(ALICE, "Alice"));

            assertThrows(IllegalArgumentException.class,
                    () -> game.addPlayer(new CraftovPlayer(ALICE, "Alice")));
            assertEquals(1, game.playerCount());
        }

        @Test
        void removePlayerReturnsTheRemovedPlayer() {
            game.transitionTo(CraftovGameState.WAITING, CREATED_AT);
            CraftovPlayer alice = new CraftovPlayer(ALICE, "Alice");
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
            game.transitionTo(CraftovGameState.WAITING, CREATED_AT);
            game.addPlayer(new CraftovPlayer(ALICE, "Alice"));
            CraftovTeam team = game.teamWithSpaceOrCreate(2);
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
            game.transitionTo(CraftovGameState.WAITING, CREATED_AT);
            CraftovPlayer alice = new CraftovPlayer(ALICE, "Alice");
            game.addPlayer(alice);

            game.markExtracted(ALICE);

            assertTrue(alice.extracted());
            assertEquals(Set.of(ALICE), game.extractedPlayerIds());
        }

        @Test
        void extractionRecordSurvivesPlayerRemoval() {
            game.transitionTo(CraftovGameState.WAITING, CREATED_AT);
            game.addPlayer(new CraftovPlayer(ALICE, "Alice"));
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
            CraftovTeam first = game.teamWithSpaceOrCreate(1);
            assertTrue(first.addMember(ALICE));

            CraftovTeam second = game.teamWithSpaceOrCreate(1);

            assertEquals(1, first.teamId());
            assertEquals(2, second.teamId());
            assertEquals(2, game.teams().size());
        }

        @Test
        void reusesTheFirstTeamWithFreeCapacity() {
            CraftovTeam first = game.teamWithSpaceOrCreate(2);
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
            CraftovResult result = new CraftovResult(GAME_ID, CREATED_AT, Set.of(ALICE), Set.of(BOB));
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
