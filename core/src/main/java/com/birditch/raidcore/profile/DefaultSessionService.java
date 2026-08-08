package com.birditch.raidcore.profile;

import com.birditch.raidcore.api.session.PlayerSession;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.time.TimeSource;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/// [SessionService] over a [LinkedHashMap] preserving join order.
///
/// Main-thread confined, like all session state.
public final class DefaultSessionService implements SessionService {

  private final TimeSource timeSource;
  private final Map<UUID, PlayerSession> sessions = new LinkedHashMap<>();

  public DefaultSessionService(TimeSource timeSource) {
    this.timeSource = Preconditions.checkNotNull(timeSource, "timeSource");
  }

  @Override
  public PlayerSession create(UUID playerId, String playerName) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(playerName, "playerName");
    PlayerSession session = new PlayerSession(playerId, playerName, timeSource.now());
    sessions.put(playerId, session);
    return session;
  }

  @Override
  public Optional<PlayerSession> find(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return Optional.ofNullable(sessions.get(playerId));
  }

  @Override
  public Optional<PlayerSession> end(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
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
