package com.birditch.raidcore.game;

import com.birditch.raidcore.api.session.PlayerSession;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.common.check.Preconditions;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/// In-memory [SessionService] double; sessions are created at [Instant#EPOCH].
final class FakeSessionService implements SessionService {

    private final Map<UUID, PlayerSession> sessions = new LinkedHashMap<>();

    @Override
    public PlayerSession create(UUID playerId, String playerName) {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(playerName, "playerName");
        PlayerSession session = new PlayerSession(playerId, playerName, Instant.EPOCH);
        session.markProfileReady();
        sessions.put(playerId, session);
        return session;
    }

    PlayerSession createLoading(UUID playerId, String playerName) {
        PlayerSession session = new PlayerSession(playerId, playerName, Instant.EPOCH);
        sessions.put(playerId, session);
        return session;
    }

    @Override
    public Optional<PlayerSession> find(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    @Override
    public Optional<PlayerSession> end(UUID playerId) {
        return Optional.ofNullable(sessions.remove(playerId));
    }

    @Override
    public Collection<PlayerSession> all() {
        return List.copyOf(sessions.values());
    }

    @Override
    public int count() {
        return sessions.size();
    }
}
