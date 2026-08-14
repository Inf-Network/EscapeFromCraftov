package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.result.Result;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// Creates and drives craftov games. Main-thread only.
public interface CraftovGameService {

    /// Fails with `"Instance limit reached (<max>)"` when the configured
    /// instance cap is already in use.
    Result<CraftovGame> create(CraftovMapId mapId);

    /// Creates a room for one exact matchmaking pool. Compatibility
    /// implementations may retain NORMAL/NORMAL behaviour.
    default Result<CraftovGame> create(CraftovMapId mapId, RaidMode mode,
                                       RaidDifficulty difficulty) {
        return create(mapId);
    }

    /// Creates a room for one canonical map action tier. A null tier is the
    /// rolling-upgrade form and must preserve the legacy mode+difficulty path.
    /// Compatibility implementations fail closed for a non-null tier instead
    /// of silently creating a room in the wrong pool.
    default Result<CraftovGame> create(CraftovMapId mapId, RaidMode mode,
                                       RaidDifficulty difficulty,
                                       @Nullable RaidTierId tierId) {
        if (tierId != null) {
            return Result.fail(GameFailures.ACTION_TIER_NOT_AVAILABLE);
        }
        return create(mapId, mode, difficulty);
    }

    Optional<CraftovGame> find(CraftovGameId id);

    Collection<CraftovGame> all();

    Result<CraftovGame> join(CraftovGameId gameId, UUID playerId);

    /// Joins using the lobby-issued team key. Equal keys deliberately share a
    /// team (future Party support); unrelated solo tickets have unique keys.
    /// Legacy implementations may retain their previous allocation behaviour.
    default Result<CraftovGame> join(CraftovGameId gameId, UUID playerId, UUID teamKey) {
        return join(gameId, playerId);
    }

    Result<CraftovGame> leave(UUID playerId);

    /**
     * Removes one explicitly named member from one WAITING room without
     * relying on the player's session pointer. Implementations that maintain
     * session state should override this method so a partially failed cross-
     * server arrival can be cancelled even when that pointer is stale.
     *
     * <p>The compatibility default fails closed. Falling back to the legacy
     * session-directed leave operation could remove a newer room when the old
     * WAITING membership is only a stale ghost.</p>
     */
    default Result<CraftovGame> cancelWaitingMember(CraftovGameId gameId, UUID playerId) {
        return Result.fail(GameFailures.WAITING_CANCELLATION_UNSUPPORTED);
    }

    Result<CraftovGame> start(CraftovGameId id);

    Result<CraftovGame> stop(CraftovGameId id);

    /// Destroys every game; never throws (failures are logged per game).
    void destroyAll();

    int activeCount();
}
