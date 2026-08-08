package com.birditch.raidcore.api.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.game.RaidGameId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlayerSessionTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-08-08T12:00:00Z");
    private static final RaidGameId GAME_ID = RaidGameId.of("abc123");

    private PlayerSession session;

    @BeforeEach
    void setUp() {
        session = new PlayerSession(PLAYER_ID, "Birditch", CREATED_AT);
    }

    @Test
    void newSessionExposesIdentityAndStartsLoading() {
        assertEquals(PLAYER_ID, session.playerId());
        assertEquals("Birditch", session.playerName());
        assertEquals(CREATED_AT, session.createdAt());
        assertEquals(ProfileLoadState.LOADING, session.profileState());
        assertEquals(Optional.empty(), session.currentGame());
    }

    @Nested
    @DisplayName("profile state")
    class ProfileState {

        @Test
        void markProfileReadyMovesToReady() {
            session.markProfileReady();
            assertEquals(ProfileLoadState.READY, session.profileState());
        }

        @Test
        void markProfileFailedMovesToFailed() {
            session.markProfileFailed();
            assertEquals(ProfileLoadState.FAILED, session.profileState());
        }
    }

    @Nested
    @DisplayName("game membership")
    class GameMembership {

        @Test
        void enterGameRecordsTheCurrentGame() {
            session.enterGame(GAME_ID);
            assertEquals(Optional.of(GAME_ID), session.currentGame());
        }

        @Test
        void enteringASecondGameThrowsIllegalStateException() {
            session.enterGame(GAME_ID);
            RaidGameId other = RaidGameId.of("def456");
            assertThrows(IllegalStateException.class, () -> session.enterGame(other));
        }

        @Test
        void leaveGameClearsTheCurrentGame() {
            session.enterGame(GAME_ID);
            session.leaveGame();
            assertEquals(Optional.empty(), session.currentGame());
        }

        @Test
        void canEnterAgainAfterLeaving() {
            session.enterGame(GAME_ID);
            session.leaveGame();
            session.enterGame(RaidGameId.of("def456"));
            assertTrue(session.currentGame().isPresent());
        }

        @Test
        void leaveGameWithoutAGameIsANoOp() {
            session.leaveGame();
            assertEquals(Optional.empty(), session.currentGame());
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void rejectsBlankPlayerName() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PlayerSession(PLAYER_ID, "  ", CREATED_AT));
        }

        @Test
        void rejectsNullArguments() {
            assertThrows(NullPointerException.class,
                    () -> new PlayerSession(null, "Birditch", CREATED_AT));
            assertThrows(NullPointerException.class,
                    () -> new PlayerSession(PLAYER_ID, null, CREATED_AT));
            assertThrows(NullPointerException.class,
                    () -> new PlayerSession(PLAYER_ID, "Birditch", null));
        }
    }
}
