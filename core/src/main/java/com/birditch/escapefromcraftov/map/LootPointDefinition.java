package com.birditch.escapefromcraftov.map;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Optional;

public record LootPointDefinition(
    String id,
    BlockPosition position,
    String containerType,
    String lootTable,
    int tier,
    boolean virtualContainer,
    boolean enabled) implements MapObject {

  public LootPointDefinition {
    MapIds.requireValid(id, "loot id");
    Preconditions.checkNotNull(position, "position");
    MapIds.requireValid(containerType, "containerType");
    MapIds.requireValid(lootTable, "lootTable");
    Preconditions.checkArgument(tier >= 1 && tier <= 4, "tier must be 1..4");
  }

  @Override public MapObjectType type() { return MapObjectType.LOOT; }
  @Override public BlockPosition anchor() { return position; }
  @Override public Optional<CuboidBounds> region() { return Optional.empty(); }
}
