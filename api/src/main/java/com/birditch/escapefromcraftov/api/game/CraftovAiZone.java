package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/** Immutable AI spawn region captured for one craftov instance. */
public record CraftovAiZone(
    String id,
    String aiType,
    int minimum,
    int maximum,
    int respawnSeconds,
    double difficulty,
    String team,
    int minX,
    int minY,
    int minZ,
    int maxX,
    int maxY,
    int maxZ) {

  public CraftovAiZone {
    Preconditions.checkNotNull(id, "id");
    Preconditions.checkNotNull(aiType, "aiType");
    Preconditions.checkNotNull(team, "team");
    Preconditions.checkArgument(minimum >= 0 && maximum >= minimum,
        "invalid AI count");
    Preconditions.checkArgument(respawnSeconds >= 0, "respawnSeconds must not be negative");
    Preconditions.checkArgument(Double.isFinite(difficulty) && difficulty >= 0,
        "difficulty must be finite and non-negative");
    Preconditions.checkArgument(maxX >= minX && maxY >= minY && maxZ >= minZ,
        "invalid AI zone bounds");
  }

  public boolean contains(double x, double y, double z) {
    return x >= minX && x < maxX + 1.0
        && y >= minY && y < maxY + 1.0
        && z >= minZ && z < maxZ + 1.0;
  }
}
