package com.birditch.escapefromcraftov.api.matchmaking;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.common.result.Result;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/// Queues players for craftov instances. Main-thread only.
public interface MatchmakingService {

    /// Fails when the player is already queued.
    Result<MatchTicket> queue(UUID playerId, CraftovMapId mapId);

    /// Queues one member of an explicit party/team. Implementations predating
    /// party support retain solo behaviour through this compatibility default;
    /// party-aware implementations should override it and persist [teamKey].
    default Result<MatchTicket> queue(UUID playerId, CraftovMapId mapId, UUID teamKey) {
        return queue(playerId, mapId);
    }

    /// Queues a solo player with an explicit ruleset. Old implementations keep
    /// NORMAL/NORMAL behaviour through the compatibility default.
    default Result<MatchTicket> queue(UUID playerId, CraftovMapId mapId, RaidMode mode,
                                      RaidDifficulty difficulty, UUID teamKey) {
        return queue(playerId, mapId, teamKey);
    }

    /// Atomically queues one complete party. Implementations must either add
    /// every member with the shared team key or add none of them.
    default Result<List<MatchTicket>> queueParty(List<UUID> playerIds, CraftovMapId mapId,
                                                 RaidMode mode, RaidDifficulty difficulty,
                                                 UUID teamKey) {
        if (playerIds.size() != 1) {
            return Result.fail(MatchmakingFailures.PARTY_QUEUE_UNSUPPORTED);
        }
        return queue(playerIds.getFirst(), mapId, mode, difficulty, teamKey).map(List::of);
    }

    /// Returns whether the player had a ticket to remove.
    boolean dequeue(UUID playerId);

    /// Atomically removes the complete queued team containing `playerId`.
    /// Solo-compatible implementations remove only that player.
    default int dequeueTeam(UUID playerId) {
        return dequeue(playerId) ? 1 : 0;
    }

    Optional<MatchTicket> ticket(UUID playerId);

    int queueSize();
}
