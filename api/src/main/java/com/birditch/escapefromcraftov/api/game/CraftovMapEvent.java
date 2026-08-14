package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Immutable map event captured for one craftov instance.
public record CraftovMapEvent(
    String id,
    String displayName,
    CraftovMapEventType type,
    String aiZoneId,
    String cacheLootPointId,
    int startDelaySeconds,
    double healthMultiplier,
    double damageMultiplier,
    int protectionSeconds,
    boolean enabled) {

  public CraftovMapEvent {
    Preconditions.checkNotNull(id, "id");
    Preconditions.checkArgument(!id.isBlank(), "id must not be blank");
    Preconditions.checkNotNull(displayName, "displayName");
    Preconditions.checkArgument(!displayName.isBlank(), "displayName must not be blank");
    Preconditions.checkNotNull(type, "type");
    Preconditions.checkNotNull(aiZoneId, "aiZoneId");
    Preconditions.checkArgument(!aiZoneId.isBlank(), "aiZoneId must not be blank");
    Preconditions.checkNotNull(cacheLootPointId, "cacheLootPointId");
    Preconditions.checkArgument(!cacheLootPointId.isBlank(),
        "cacheLootPointId must not be blank");
    Preconditions.checkArgument(startDelaySeconds >= 0 && startDelaySeconds <= 3_600,
        "startDelaySeconds must be 0..3600");
    Preconditions.checkArgument(Double.isFinite(healthMultiplier)
            && healthMultiplier > 0 && healthMultiplier <= 20,
        "healthMultiplier must be finite and in (0, 20]");
    Preconditions.checkArgument(Double.isFinite(damageMultiplier)
            && damageMultiplier > 0 && damageMultiplier <= 8,
        "damageMultiplier must be finite and in (0, 8]");
    Preconditions.checkArgument(protectionSeconds >= 0 && protectionSeconds <= 300,
        "protectionSeconds must be 0..300");
  }
}
