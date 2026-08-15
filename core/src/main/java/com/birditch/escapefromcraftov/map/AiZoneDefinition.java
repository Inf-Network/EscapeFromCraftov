package com.birditch.escapefromcraftov.map;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import java.util.List;
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
    boolean enabled,
    List<BlockPosition> patrolWaypoints) implements MapObject {

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
    patrolWaypoints = List.copyOf(Preconditions.checkNotNull(
        patrolWaypoints, "patrolWaypoints"));
    Preconditions.checkArgument(
        patrolWaypoints.size() <= CraftovAiZone.MAXIMUM_PATROL_WAYPOINTS,
        "patrolWaypoints must contain at most " + CraftovAiZone.MAXIMUM_PATROL_WAYPOINTS
            + " points");
  }

  /** Source-compatible constructor for AI zones authored before patrol routes. */
  public AiZoneDefinition(String id, CuboidBounds bounds, String aiType, int minimum,
      int maximum, int respawnSeconds, double difficulty, String team, boolean enabled) {
    this(id, bounds, aiType, minimum, maximum, respawnSeconds, difficulty, team, enabled,
        List.of());
  }

  @Override public MapObjectType type() { return MapObjectType.AI_ZONE; }
  @Override public BlockPosition anchor() { return bounds.center(); }
  @Override public Optional<CuboidBounds> region() { return Optional.of(bounds); }
}
