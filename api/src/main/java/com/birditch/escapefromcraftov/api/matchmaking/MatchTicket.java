package com.birditch.escapefromcraftov.api.matchmaking;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// A player's place in the matchmaking queue.
///
/// [#teamKey] identifies players that deliberately queued as one party. A solo
/// ticket initially uses its own player UUID; an auto-fill matchmaker may issue
/// the final assignments with one shared key for a squad formed from solo tickets.
public record MatchTicket(UUID playerId, CraftovMapId mapId, Instant queuedAt, UUID teamKey,
                          RaidMode mode, RaidDifficulty difficulty,
                          @Nullable RaidTierId tierId, SquadFillMode fillMode) {

    /// Creates the default solo ticket. Party matchmaking may use the
    /// four-argument constructor with one shared key for all party members.
    public MatchTicket(UUID playerId, CraftovMapId mapId, Instant queuedAt) {
        this(playerId, mapId, queuedAt, playerId, RaidMode.NORMAL, RaidDifficulty.NORMAL, null,
                SquadFillMode.NO_FILL);
    }

    public MatchTicket(UUID playerId, CraftovMapId mapId, Instant queuedAt, UUID teamKey) {
        this(playerId, mapId, queuedAt, teamKey, RaidMode.NORMAL, RaidDifficulty.NORMAL, null,
                SquadFillMode.NO_FILL);
    }

    public MatchTicket(UUID playerId, CraftovMapId mapId, Instant queuedAt, UUID teamKey,
                       RaidMode mode, RaidDifficulty difficulty) {
        this(playerId, mapId, queuedAt, teamKey, mode, difficulty, null,
                SquadFillMode.NO_FILL);
    }

    /// Source-compatible constructor for payload producers written before squad fill.
    public MatchTicket(UUID playerId, CraftovMapId mapId, Instant queuedAt, UUID teamKey,
                       RaidMode mode, RaidDifficulty difficulty, @Nullable RaidTierId tierId) {
        this(playerId, mapId, queuedAt, teamKey, mode, difficulty, tierId,
                SquadFillMode.NO_FILL);
    }

    public MatchTicket {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(mapId, "mapId");
        Preconditions.checkNotNull(queuedAt, "queuedAt");
        if (teamKey == null) {
            teamKey = playerId;
        }
        // Gson passes null for fields absent in rolling-upgrade Redis JSON.
        if (mode == null) {
            mode = RaidMode.NORMAL;
        }
        if (difficulty == null) {
            difficulty = RaidDifficulty.NORMAL;
        }
        // Rolling-upgrade JSON has no fillMode. Preserve its historical solo behaviour.
        if (fillMode == null) {
            fillMode = SquadFillMode.NO_FILL;
        }
    }
}
