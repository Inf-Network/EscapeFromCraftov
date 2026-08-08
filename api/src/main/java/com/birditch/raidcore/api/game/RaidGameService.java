package com.birditch.raidcore.api.game;

import com.birditch.raidcore.common.result.Result;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/// Creates and drives raid games. Main-thread only.
public interface RaidGameService {

    /// Fails with `"Instance limit reached (<max>)"` when the configured
    /// instance cap is already in use.
    Result<RaidGame> create(RaidMapId mapId);

    Optional<RaidGame> find(RaidGameId id);

    Collection<RaidGame> all();

    Result<RaidGame> join(RaidGameId gameId, UUID playerId);

    Result<RaidGame> leave(UUID playerId);

    Result<RaidGame> start(RaidGameId id);

    Result<RaidGame> stop(RaidGameId id);

    /// Destroys every game; never throws (failures are logged per game).
    void destroyAll();

    int activeCount();
}
