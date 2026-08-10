package com.birditch.raidcore.api.network;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.UUID;

/// Routing decision made by the lobby: which server and raid instance a player
/// should join next.
public record MatchAssignment(UUID playerId, ServerId serverId, String proxyName,
                              RaidGameId gameId, RaidMapId mapId) {

    public MatchAssignment {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(serverId, "serverId");
        Preconditions.checkNotNull(proxyName, "proxyName");
        Preconditions.checkArgument(!proxyName.isBlank(), "proxyName must not be blank");
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(mapId, "mapId");
    }
}
