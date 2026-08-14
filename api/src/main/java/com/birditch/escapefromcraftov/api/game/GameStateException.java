package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.api.CraftovException;

/// Raised on an illegal [CraftovGameState] transition.
public class GameStateException extends CraftovException {

    public GameStateException(CraftovGameId gameId, CraftovGameState from, CraftovGameState to) {
        super("Illegal state transition for game " + gameId.value() + ": " + from + " -> " + to);
    }
}
