package com.birditch.escapefromcraftov.api.event.game;

import com.birditch.escapefromcraftov.api.event.CraftovEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Published when a craftov game transitions into its running phase.
public record CraftovStartedEvent(CraftovGameId gameId) implements CraftovEvent {

    public CraftovStartedEvent {
        Preconditions.checkNotNull(gameId, "gameId");
    }
}
