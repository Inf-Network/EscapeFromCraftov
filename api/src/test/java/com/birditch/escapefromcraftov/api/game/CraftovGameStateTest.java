package com.birditch.escapefromcraftov.api.game;

import static com.birditch.escapefromcraftov.api.game.CraftovGameState.CREATED;
import static com.birditch.escapefromcraftov.api.game.CraftovGameState.DESTROYED;
import static com.birditch.escapefromcraftov.api.game.CraftovGameState.ENDING;
import static com.birditch.escapefromcraftov.api.game.CraftovGameState.FINISHED;
import static com.birditch.escapefromcraftov.api.game.CraftovGameState.PREPARING;
import static com.birditch.escapefromcraftov.api.game.CraftovGameState.RUNNING;
import static com.birditch.escapefromcraftov.api.game.CraftovGameState.STARTING;
import static com.birditch.escapefromcraftov.api.game.CraftovGameState.WAITING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CraftovGameStateTest {

    private static final Map<CraftovGameState, Set<CraftovGameState>> ALLOWED = Map.of(
            CREATED, Set.of(WAITING, DESTROYED),
            WAITING, Set.of(PREPARING, STARTING, ENDING, DESTROYED),
            PREPARING, Set.of(STARTING, ENDING, DESTROYED),
            STARTING, Set.of(RUNNING, ENDING),
            RUNNING, Set.of(ENDING),
            ENDING, Set.of(FINISHED),
            FINISHED, Set.of(DESTROYED),
            DESTROYED, Set.of());

    @ParameterizedTest
    @EnumSource(CraftovGameState.class)
    @DisplayName("canTransitionTo matches the full contract matrix")
    void transitionMatrix(CraftovGameState from) {
        Set<CraftovGameState> allowed = ALLOWED.get(from);
        for (CraftovGameState next : CraftovGameState.values()) {
            assertEquals(allowed.contains(next), from.canTransitionTo(next),
                    () -> from + " -> " + next);
        }
    }

    @ParameterizedTest
    @EnumSource(names = {"STARTING", "RUNNING"})
    void startingAndRunningAreActive(CraftovGameState state) {
        assertTrue(state.active());
    }

    @ParameterizedTest
    @EnumSource(names = {"STARTING", "RUNNING"}, mode = EnumSource.Mode.EXCLUDE)
    void everyOtherStateIsInactive(CraftovGameState state) {
        assertFalse(state.active());
    }

    @ParameterizedTest
    @EnumSource(CraftovGameState.class)
    void onlyDestroyedIsTerminal(CraftovGameState state) {
        assertEquals(state == DESTROYED, state.terminal());
    }
}
