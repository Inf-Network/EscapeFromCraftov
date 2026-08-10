package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.Optional;

public record LootZoneDefinition(
    String id,
    CuboidBounds bounds,
    String lootTable,
    int tier,
    double density,
    double danger,
    boolean enabled) implements MapObject {

  public LootZoneDefinition {
    MapIds.requireValid(id, "loot zone id");
    Preconditions.checkNotNull(bounds, "bounds");
    MapIds.requireValid(lootTable, "lootTable");
    Preconditions.checkArgument(tier >= 1 && tier <= 4, "tier must be 1..4");
    Preconditions.checkArgument(Double.isFinite(density) && density > 0,
        "density must be finite and positive");
    Preconditions.checkArgument(Double.isFinite(danger) && danger >= 0 && danger <= 1,
        "danger must be 0..1");
  }

  @Override public MapObjectType type() { return MapObjectType.LOOT_ZONE; }
  @Override public BlockPosition anchor() { return bounds.center(); }
  @Override public Optional<CuboidBounds> region() { return Optional.of(bounds); }
}
