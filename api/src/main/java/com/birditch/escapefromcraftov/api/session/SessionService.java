package com.birditch.escapefromcraftov.api.session;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/// Tracks live player sessions. Main-thread only.
public interface SessionService {

    /// Creates a fresh session, replacing any stale one for the same player.
    PlayerSession create(UUID playerId, String playerName);

    Optional<PlayerSession> find(UUID playerId);

    /// Removes and returns the session, if present.
    Optional<PlayerSession> end(UUID playerId);

    Collection<PlayerSession> all();

    int count();
}
