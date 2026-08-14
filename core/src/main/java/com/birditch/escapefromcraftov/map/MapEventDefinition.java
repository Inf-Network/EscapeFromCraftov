package com.birditch.escapefromcraftov.map;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Human-authored configuration for one map-scoped gameplay event.
public record MapEventDefinition(
    String id,
    String displayName,
    MapEventType type,
    String aiZoneId,
    String cacheLootPointId,
    int startDelaySeconds,
    double healthMultiplier,
    double damageMultiplier,
    int protectionSeconds,
    boolean enabled) {

  public MapEventDefinition {
    MapIds.requireValid(id, "map event id");
    Preconditions.checkNotNull(displayName, "displayName");
    Preconditions.checkArgument(!displayName.isBlank(), "displayName must not be blank");
    Preconditions.checkNotNull(type, "type");
    MapIds.requireValid(aiZoneId, "aiZoneId");
    MapIds.requireValid(cacheLootPointId, "cacheLootPointId");
    // Tuning ranges are reported by ManualMapValidator so one bad value does not hide
    // the remaining authoring errors in the same map revision.
  }
}
