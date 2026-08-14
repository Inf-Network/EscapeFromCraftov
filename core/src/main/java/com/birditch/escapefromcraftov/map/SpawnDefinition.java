package com.birditch.escapefromcraftov.map;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public record SpawnDefinition(
    String id,
    BlockPosition position,
    float yaw,
    float pitch,
    String team,
    double weight,
    boolean enabled,
    @Nullable CuboidBounds spawnRegion) implements MapObject {

  public SpawnDefinition {
    MapIds.requireValid(id, "spawn id");
    Preconditions.checkNotNull(position, "position");
    Preconditions.checkNotNull(team, "team");
    Preconditions.checkArgument(!team.isBlank(), "team must not be blank");
    Preconditions.checkArgument(Double.isFinite(weight) && weight > 0,
        "weight must be finite and positive");
  }

  @Override public MapObjectType type() { return MapObjectType.SPAWN; }
  @Override public BlockPosition anchor() { return position; }
  @Override public Optional<CuboidBounds> region() { return Optional.ofNullable(spawnRegion); }
}
