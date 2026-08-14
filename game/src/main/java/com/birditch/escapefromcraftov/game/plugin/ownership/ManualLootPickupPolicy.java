package com.birditch.escapefromcraftov.game.plugin.ownership;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/** Pure event boundary used by the post-ownership carry pickup listener. */
final class ManualLootPickupPolicy {

  private ManualLootPickupPolicy() {}

  static boolean mayTake(boolean eventCancelled, boolean activeCarry, LootAccess access) {
    Preconditions.checkNotNull(access, "access");
    return !eventCancelled && activeCarry && access.allowed();
  }
}
