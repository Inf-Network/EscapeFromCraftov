package com.birditch.escapefromcraftov.game.plugin.carry;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/** Pure final-event gate for mutations that replace a vanilla inventory operation. */
final class RaidCarryEventPolicy {

  private RaidCarryEventPolicy() {}

  static boolean mayCommit(boolean cancelled, boolean activeCarry) {
    return !cancelled && activeCarry;
  }

  static int clickedDropQuantity(boolean dropOne, int available) {
    Preconditions.checkArgument(available >= 1, "available must be positive");
    return dropOne ? 1 : available;
  }
}
