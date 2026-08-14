package com.birditch.escapefromcraftov.api.event.game;

import com.birditch.escapefromcraftov.api.event.CraftovEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovResult;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Published after a craftov game has finished and its result is available.
public record CraftovEndedEvent(CraftovGameId gameId, CraftovResult result) implements CraftovEvent {

    public CraftovEndedEvent {
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(result, "result");
    }
}
