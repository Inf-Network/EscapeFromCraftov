package com.birditch.escapefromcraftov.game.plugin.carry;

import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/** Pure equality gate between the HIGHEST drop snapshot and the MONITOR commit view. */
final class RaidDropCommitPolicy {

  private RaidDropCommitPolicy() {}

  static boolean mayCommit(boolean cancelled, DropStamp frozen, DropStamp current) {
    if (cancelled || !Preconditions.checkNotNull(frozen, "frozen")
        .equals(Preconditions.checkNotNull(current, "current"))) return false;
    try {
      return Math.addExact(current.droppedAmount(), current.remainingAmount())
          == current.originalAmount();
    } catch (ArithmeticException invalidAmount) {
      return false;
    }
  }

  record DropStamp(
      UUID playerId,
      int slot,
      UUID entityId,
      UUID instanceId,
      ItemId itemId,
      int droppedAmount,
      int remainingAmount,
      int originalAmount,
      long layoutVersion) {

    DropStamp {
      Preconditions.checkNotNull(playerId, "playerId");
      Preconditions.checkArgument(slot >= 0, "slot must not be negative");
      Preconditions.checkNotNull(entityId, "entityId");
      Preconditions.checkNotNull(instanceId, "instanceId");
      Preconditions.checkNotNull(itemId, "itemId");
      Preconditions.checkArgument(droppedAmount > 0, "droppedAmount must be positive");
      Preconditions.checkArgument(remainingAmount >= 0, "remainingAmount must not be negative");
      Preconditions.checkArgument(originalAmount > 0, "originalAmount must be positive");
      Preconditions.checkArgument(layoutVersion >= 0, "layoutVersion must not be negative");
    }
  }
}
