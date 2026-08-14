package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import org.jspecify.annotations.Nullable;

/// Immutable weighted spawn definition captured for one craftov instance.
public record CraftovSpawnLocation(
    String id, int x, int y, int z, float yaw, float pitch,
    String team, double weight, @Nullable CraftovSpawnArea region) {

  public CraftovSpawnLocation {
    Preconditions.checkNotNull(id, "id");
    Preconditions.checkArgument(!id.isBlank(), "id must not be blank");
    Preconditions.checkNotNull(team, "team");
    Preconditions.checkArgument(!team.isBlank(), "team must not be blank");
    Preconditions.checkArgument(Double.isFinite(weight) && weight > 0,
        "weight must be finite and positive");
  }

  public CraftovSpawnLocation(String id, int x, int y, int z, float yaw, float pitch) {
    this(id, x, y, z, yaw, pitch, "players", 1.0, null);
  }
}
