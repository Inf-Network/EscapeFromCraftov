package com.birditch.escapefromcraftov.api.network;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/// Routing decision made by the lobby: which server and craftov instance a player
/// should join next.
public record MatchAssignment(UUID playerId, ServerId serverId, String proxyName,
                              CraftovGameId gameId, CraftovMapId mapId) {

    public MatchAssignment {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(serverId, "serverId");
        Preconditions.checkNotNull(proxyName, "proxyName");
        Preconditions.checkArgument(!proxyName.isBlank(), "proxyName must not be blank");
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(mapId, "mapId");
    }
}
