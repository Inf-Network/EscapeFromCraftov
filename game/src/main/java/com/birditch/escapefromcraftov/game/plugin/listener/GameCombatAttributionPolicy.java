package com.birditch.escapefromcraftov.game.plugin.listener;

/** Pure decision for same-team damage and the previous hostile attribution. */
final class GameCombatAttributionPolicy {

  private GameCombatAttributionPolicy() {}

  static FriendlyDamage friendlyDamage(boolean friendlyFireEnabled) {
    return friendlyFireEnabled
        ? FriendlyDamage.ALLOW_AND_DEFER_CLEAR
        : FriendlyDamage.CANCEL_AND_KEEP_HOSTILE;
  }

  static boolean clearsHostileAfterEvent(boolean friendlyFireEnabled,
      boolean cancelled, double finalDamage) {
    return friendlyFireEnabled && !cancelled && finalDamage > 0;
  }

  enum FriendlyDamage {
    CANCEL_AND_KEEP_HOSTILE,
    ALLOW_AND_DEFER_CLEAR
  }
}
