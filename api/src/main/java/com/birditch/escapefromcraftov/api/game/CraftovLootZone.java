package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.api.loot.LootTableId;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/** Immutable authored area that turns real Minecraft containers into raid loot. */
public record CraftovLootZone(
    String id,
    LootTableId lootTableId,
    int tier,
    double density,
    double danger,
    int minX,
    int minY,
    int minZ,
    int maxX,
    int maxY,
    int maxZ) {

  public CraftovLootZone {
    Preconditions.checkNotNull(id, "id");
    Preconditions.checkArgument(!id.isBlank(), "id must not be blank");
    Preconditions.checkNotNull(lootTableId, "lootTableId");
    Preconditions.checkArgument(tier >= 1 && tier <= 4, "tier must be 1..4");
    Preconditions.checkArgument(Double.isFinite(density) && density > 0,
        "density must be finite and positive");
    Preconditions.checkArgument(Double.isFinite(danger) && danger >= 0 && danger <= 1,
        "danger must be 0..1");
    Preconditions.checkArgument(minX <= maxX && minY <= maxY && minZ <= maxZ,
        "zone minimums must not exceed maximums");
  }

  public boolean contains(int x, int y, int z) {
    return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
  }
}
