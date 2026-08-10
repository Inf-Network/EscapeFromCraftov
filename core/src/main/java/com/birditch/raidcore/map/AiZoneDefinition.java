package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.Optional;

public record AiZoneDefinition(
    String id,
    CuboidBounds bounds,
    String aiType,
    int minimum,
    int maximum,
    int respawnSeconds,
    double difficulty,
    String team,
    boolean enabled) implements MapObject {

  public AiZoneDefinition {
    MapIds.requireValid(id, "AI zone id");
    Preconditions.checkNotNull(bounds, "bounds");
    MapIds.requireValid(aiType, "aiType");
    Preconditions.checkArgument(minimum >= 0 && maximum >= minimum, "invalid AI count");
    Preconditions.checkArgument(respawnSeconds >= 0, "respawnSeconds must not be negative");
    Preconditions.checkArgument(Double.isFinite(difficulty) && difficulty >= 0,
        "difficulty must be finite and non-negative");
    Preconditions.checkNotNull(team, "team");
    Preconditions.checkArgument(!team.isBlank(), "team must not be blank");
  }

  @Override public MapObjectType type() { return MapObjectType.AI_ZONE; }
  @Override public BlockPosition anchor() { return bounds.center(); }
  @Override public Optional<CuboidBounds> region() { return Optional.of(bounds); }
}
