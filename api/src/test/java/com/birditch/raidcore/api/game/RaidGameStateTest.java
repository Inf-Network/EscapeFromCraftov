package com.birditch.raidcore.api.game;

import static com.birditch.raidcore.api.game.RaidGameState.CREATED;
import static com.birditch.raidcore.api.game.RaidGameState.DESTROYED;
import static com.birditch.raidcore.api.game.RaidGameState.ENDING;
import static com.birditch.raidcore.api.game.RaidGameState.FINISHED;
import static com.birditch.raidcore.api.game.RaidGameState.PREPARING;
import static com.birditch.raidcore.api.game.RaidGameState.RUNNING;
import static com.birditch.raidcore.api.game.RaidGameState.STARTING;
import static com.birditch.raidcore.api.game.RaidGameState.WAITING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RaidGameStateTest {

    private static final Map<RaidGameState, Set<RaidGameState>> ALLOWED = Map.of(
            CREATED, Set.of(WAITING, DESTROYED),
            WAITING, Set.of(PREPARING, STARTING, ENDING, DESTROYED),
            PREPARING, Set.of(STARTING, ENDING, DESTROYED),
            STARTING, Set.of(RUNNING, ENDING),
            RUNNING, Set.of(ENDING),
            ENDING, Set.of(FINISHED),
            FINISHED, Set.of(DESTROYED),
            DESTROYED, Set.of());

    @ParameterizedTest
    @EnumSource(RaidGameState.class)
    @DisplayName("canTransitionTo matches the full contract matrix")
    void transitionMatrix(RaidGameState from) {
        Set<RaidGameState> allowed = ALLOWED.get(from);
        for (RaidGameState next : RaidGameState.values()) {
            assertEquals(allowed.contains(next), from.canTransitionTo(next),
                    () -> from + " -> " + next);
        }
    }

    @ParameterizedTest
    @EnumSource(names = {"STARTING", "RUNNING"})
    void startingAndRunningAreActive(RaidGameState state) {
        assertTrue(state.active());
    }

    @ParameterizedTest
    @EnumSource(names = {"STARTING", "RUNNING"}, mode = EnumSource.Mode.EXCLUDE)
    void everyOtherStateIsInactive(RaidGameState state) {
        assertFalse(state.active());
    }

    @ParameterizedTest
    @EnumSource(RaidGameState.class)
    void onlyDestroyedIsTerminal(RaidGameState state) {
        assertEquals(state == DESTROYED, state.terminal());
    }
}
