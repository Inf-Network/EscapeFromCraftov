package com.birditch.raidcore.api.network;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.common.check.Preconditions;

/// Point-in-time view of a single raid instance as advertised on the network.
public record InstanceSnapshot(RaidGameId gameId, RaidMapId mapId, RaidGameState state,
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
