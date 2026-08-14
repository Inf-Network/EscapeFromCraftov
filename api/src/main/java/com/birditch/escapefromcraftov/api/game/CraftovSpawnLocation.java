package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import org.jspecify.annotations.Nullable;

/// Immutable weighted spawn definition captured for one craftov instance.
public record CraftovSpawnLocation(
    String id, int x, int y, int z, float yaw, float pitch,
    String team, double weight, @Nullable CraftovSpawnArea region, String routeGroup) {

  public CraftovSpawnLocation {
    Preconditions.checkNotNull(id, "id");
    Preconditions.checkArgument(!id.isBlank(), "id must not be blank");
    Preconditions.checkNotNull(team, "team");
    Preconditions.checkArgument(!team.isBlank(), "team must not be blank");
    Preconditions.checkArgument(Double.isFinite(weight) && weight > 0,
        "weight must be finite and positive");
    Preconditions.checkNotNull(routeGroup, "routeGroup");
    Preconditions.checkArgument(routeGroup.matches("[a-z0-9_]+"),
        "routeGroup must match [a-z0-9_]+");
  }

  public CraftovSpawnLocation(String id, int x, int y, int z, float yaw, float pitch,
      String team, double weight, @Nullable CraftovSpawnArea region) {
    this(id, x, y, z, yaw, pitch, team, weight, region, legacyRouteGroup(id));
  }

  public CraftovSpawnLocation(String id, int x, int y, int z, float yaw, float pitch) {
    this(id, x, y, z, yaw, pitch, "players", 1.0, null, legacyRouteGroup(id));
  }

  private static String legacyRouteGroup(String id) {
    if (id != null && id.matches("[a-z0-9_]+")) return id;
    int hash = id == null ? 0 : id.hashCode();
    return "legacy_" + Integer.toUnsignedString(hash, 36);
  }
}
