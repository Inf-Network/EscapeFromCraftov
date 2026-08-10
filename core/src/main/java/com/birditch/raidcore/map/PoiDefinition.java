package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.Optional;

public record PoiDefinition(
    String id,
    String displayName,
    CuboidBounds bounds,
    int tier,
    boolean enabled) implements MapObject {

  public PoiDefinition {
    MapIds.requireValid(id, "POI id");
    Preconditions.checkNotNull(displayName, "displayName");
    Preconditions.checkArgument(!displayName.isBlank(), "displayName must not be blank");
    Preconditions.checkNotNull(bounds, "bounds");
    Preconditions.checkArgument(tier >= 1 && tier <= 4, "tier must be 1..4");
  }

  @Override public MapObjectType type() { return MapObjectType.POI; }
  @Override public BlockPosition anchor() { return bounds.center(); }
  @Override public Optional<CuboidBounds> region() { return Optional.of(bounds); }
}
