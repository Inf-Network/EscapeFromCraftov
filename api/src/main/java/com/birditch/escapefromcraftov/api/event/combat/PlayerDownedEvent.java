package com.birditch.escapefromcraftov.api.event.combat;

import com.birditch.escapefromcraftov.api.event.CraftovEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/// Published when a player is downed during a craftov.
public record PlayerDownedEvent(UUID playerId, CraftovGameId gameId) implements CraftovEvent {

    public PlayerDownedEvent {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(gameId, "gameId");
    }
}
