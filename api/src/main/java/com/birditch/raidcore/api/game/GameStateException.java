package com.birditch.raidcore.api.game;

import com.birditch.raidcore.api.RaidException;

/// Raised on an illegal [RaidGameState] transition.
public class GameStateException extends RaidException {

    public GameStateException(RaidGameId gameId, RaidGameState from, RaidGameState to) {
        super("Illegal state transition for game " + gameId.value() + ": " + from + " -> " + to);
    }
}
