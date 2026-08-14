package com.birditch.escapefromcraftov.game.plugin.editor;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/// One expiring destructive action per administrator.
public final class EditorConfirmationService {

  private final Map<UUID, Pending> pending = new HashMap<>();
  private final Clock clock;
  private final Duration timeout;

  public EditorConfirmationService() {
    this(Clock.systemUTC(), Duration.ofSeconds(15));
  }

  EditorConfirmationService(Clock clock) {
    this(clock, Duration.ofSeconds(15));
  }

  public EditorConfirmationService(Duration timeout) {
    this(Clock.systemUTC(), timeout);
  }

  EditorConfirmationService(Clock clock, Duration timeout) {
    this.clock = Preconditions.checkNotNull(clock, "clock");
    this.timeout = Preconditions.checkNotNull(timeout, "timeout");
    Preconditions.checkArgument(!timeout.isNegative() && !timeout.isZero(),
        "timeout must be positive");
  }

  public void request(UUID playerId, String description, Runnable action) {
    pending.put(Preconditions.checkNotNull(playerId, "playerId"),
        new Pending(Preconditions.checkNotNull(description, "description"),
            clock.instant().plus(timeout), Preconditions.checkNotNull(action, "action")));
  }

  public Optional<String> confirm(UUID playerId) {
    Pending value = pending.remove(Preconditions.checkNotNull(playerId, "playerId"));
    if (value == null || clock.instant().isAfter(value.expiresAt())) return Optional.empty();
    value.action().run();
    return Optional.of(value.description());
  }

  public void cancel(UUID playerId) {
    pending.remove(Preconditions.checkNotNull(playerId, "playerId"));
  }

  private record Pending(String description, Instant expiresAt, Runnable action) {}
}
