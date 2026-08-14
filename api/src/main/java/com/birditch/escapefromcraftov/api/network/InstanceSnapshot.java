package com.birditch.escapefromcraftov.api.network;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Point-in-time view of a single craftov instance as advertised on the network.
public record InstanceSnapshot(CraftovGameId gameId, CraftovMapId mapId, CraftovGameState state,
                               int playerCount, int maxPlayers, boolean worldReady) {

    public InstanceSnapshot {
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(mapId, "mapId");
        Preconditions.checkNotNull(state, "state");
        Preconditions.checkArgument(playerCount >= 0,
                "playerCount must not be negative but was: " + playerCount);
        Preconditions.checkArgument(maxPlayers >= 1,
                "maxPlayers must be at least 1 but was: " + maxPlayers);
    }
}
