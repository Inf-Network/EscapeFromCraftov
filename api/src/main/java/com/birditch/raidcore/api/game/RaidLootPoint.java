package com.birditch.raidcore.api.game;

import com.birditch.raidcore.api.loot.LootContainerType;
import com.birditch.raidcore.api.loot.LootTableId;
import com.birditch.raidcore.common.check.Preconditions;

/// Immutable Loot Point captured with one raid's map revision.
public record RaidLootPoint(
    String id,
    int x,
    int y,
    int z,
    LootContainerType containerType,
    LootTableId lootTableId,
    int tier,
    boolean virtualContainer) {

  public RaidLootPoint {
    Preconditions.checkNotNull(id, "id");
    Preconditions.checkArgument(!id.isBlank(), "id must not be blank");
    Preconditions.checkNotNull(containerType, "containerType");
    Preconditions.checkNotNull(lootTableId, "lootTableId");
    Preconditions.checkArgument(tier >= 1 && tier <= 4, "tier must be 1..4");
  }

  public double distanceSquared(double otherX, double otherY, double otherZ) {
    double dx = x + 0.5 - otherX;
    double dy = y + 0.5 - otherY;
    double dz = z + 0.5 - otherZ;
    return dx * dx + dy * dy + dz * dz;
  }
}
