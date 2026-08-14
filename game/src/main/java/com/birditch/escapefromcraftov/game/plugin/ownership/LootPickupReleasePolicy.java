package com.birditch.escapefromcraftov.game.plugin.ownership;

/** Pure commit gate for stripping ownership only after a pickup survives all cancellers. */
final class LootPickupReleasePolicy {

  private LootPickupReleasePolicy() {}

  static boolean shouldRelease(boolean cancelled, int remaining, LootAccess access) {
    return !cancelled && remaining == 0 && access.allowed();
  }
}
