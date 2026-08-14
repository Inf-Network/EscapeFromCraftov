package com.birditch.escapefromcraftov.api.event.game;

import com.birditch.escapefromcraftov.api.event.CraftovEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/// Published after a player has joined a craftov game.
public record PlayerJoinedCraftovEvent(CraftovGameId gameId, UUID playerId) implements CraftovEvent {

    public PlayerJoinedCraftovEvent {
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(playerId, "playerId");
    }
}
