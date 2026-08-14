package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.result.Result;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/// Creates and drives craftov games. Main-thread only.
public interface CraftovGameService {

    /// Fails with `"Instance limit reached (<max>)"` when the configured
    /// instance cap is already in use.
    Result<CraftovGame> create(CraftovMapId mapId);

    Optional<CraftovGame> find(CraftovGameId id);

    Collection<CraftovGame> all();

    Result<CraftovGame> join(CraftovGameId gameId, UUID playerId);

    Result<CraftovGame> leave(UUID playerId);

    Result<CraftovGame> start(CraftovGameId id);

    Result<CraftovGame> stop(CraftovGameId id);

    /// Destroys every game; never throws (failures are logged per game).
    void destroyAll();

    int activeCount();
}
