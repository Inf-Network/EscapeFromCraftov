package com.birditch.escapefromcraftov.api.network;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/// Routing decision made by the lobby: which server and craftov instance a player
/// should join next.
public record MatchAssignment(UUID playerId, ServerId serverId, String proxyName,
                              CraftovGameId gameId, CraftovMapId mapId, UUID teamKey,
                              RaidMode mode, RaidDifficulty difficulty) {

    /// Creates a solo assignment whose team key cannot collide with another
    /// player's ticket. Party matchmaking supplies a shared key explicitly.
    public MatchAssignment(UUID playerId, ServerId serverId, String proxyName,
                           CraftovGameId gameId, CraftovMapId mapId) {
        this(playerId, serverId, proxyName, gameId, mapId, playerId,
                RaidMode.NORMAL, RaidDifficulty.NORMAL);
    }

    public MatchAssignment(UUID playerId, ServerId serverId, String proxyName,
                           CraftovGameId gameId, CraftovMapId mapId, UUID teamKey) {
        this(playerId, serverId, proxyName, gameId, mapId, teamKey,
                RaidMode.NORMAL, RaidDifficulty.NORMAL);
    }

    public MatchAssignment {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(serverId, "serverId");
        Preconditions.checkNotNull(proxyName, "proxyName");
        Preconditions.checkArgument(!proxyName.isBlank(), "proxyName must not be blank");
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(mapId, "mapId");
        // Rolling upgrades may consume a pending assignment written before
        // teamKey existed. Treat that payload exactly like a solo ticket.
        if (teamKey == null) {
            teamKey = playerId;
        }
        if (mode == null) {
            mode = RaidMode.NORMAL;
        }
        if (difficulty == null) {
            difficulty = RaidDifficulty.NORMAL;
        }
    }
}
