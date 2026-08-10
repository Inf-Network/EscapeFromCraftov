package com.birditch.raidcore.api.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.birditch.raidcore.api.game.RaidGameId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExtractionSessionTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final RaidGameId GAME_ID = RaidGameId.of("abc123");
    private static final ExtractionPointId POINT_ID = ExtractionPointId.of("north_gate");
    private static final Instant STARTED_AT = Instant.parse("2026-08-08T12:00:00Z");

    private ExtractionSession session;

    @BeforeEach
    void setUp() {
        session = new ExtractionSession(PLAYER_ID, GAME_ID, POINT_ID, STARTED_AT);
    }

    @Test
    void startsExtractingWithItsIdentity() {
        assertEquals(PLAYER_ID, session.playerId());
        assertEquals(GAME_ID, session.gameId());
        assertEquals(POINT_ID, session.pointId());
        assertEquals(STARTED_AT, session.startedAt());
        assertEquals(ExtractionState.EXTRACTING, session.state());
    }

    @Nested
    @DisplayName("settling")
    class Settling {

        @Test
        void timerThenDurableSettlementMoveThroughBothStates() {
            session.beginSettlement();
            assertEquals(ExtractionState.SETTLING, session.state());
            session.complete();
            assertEquals(ExtractionState.COMPLETED, session.state());
        }

        @Test
        void failMovesToFailed() {
            session.fail();
            assertEquals(ExtractionState.FAILED, session.state());
        }

        @Test
        void completeBeforeSettlementThrowsIllegalStateException() {
            assertThrows(IllegalStateException.class, session::complete);
        }

        @Test
        void completeTwiceThrowsIllegalStateException() {
            session.beginSettlement();
            session.complete();
            assertThrows(IllegalStateException.class, session::complete);
        }

        @Test
        void failAfterCompleteThrowsIllegalStateException() {
            session.beginSettlement();
            session.complete();
            assertThrows(IllegalStateException.class, session::fail);
        }

        @Test
        void completeAfterFailThrowsIllegalStateException() {
            session.fail();
            assertThrows(IllegalStateException.class, session::complete);
        }

        @Test
        void pendingSettlementCanFail() {
            session.beginSettlement();
            session.fail();
            assertEquals(ExtractionState.FAILED, session.state());
        }
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class,
                () -> new ExtractionSession(null, GAME_ID, POINT_ID, STARTED_AT));
        assertThrows(NullPointerException.class,
                () -> new ExtractionSession(PLAYER_ID, null, POINT_ID, STARTED_AT));
        assertThrows(NullPointerException.class,
                () -> new ExtractionSession(PLAYER_ID, GAME_ID, null, STARTED_AT));
        assertThrows(NullPointerException.class,
                () -> new ExtractionSession(PLAYER_ID, GAME_ID, POINT_ID, null));
    }
}
