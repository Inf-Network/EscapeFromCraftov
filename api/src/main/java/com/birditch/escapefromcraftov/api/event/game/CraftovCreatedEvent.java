package com.birditch.escapefromcraftov.api.event.game;

import com.birditch.escapefromcraftov.api.event.CraftovEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Published after a craftov game has been created and is waiting for players.
public record CraftovCreatedEvent(CraftovGameId gameId, CraftovMapId mapId) implements CraftovEvent {

    public CraftovCreatedEvent {
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(mapId, "mapId");
    }
}
