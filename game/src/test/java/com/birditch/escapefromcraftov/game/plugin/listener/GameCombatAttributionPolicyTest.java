package com.birditch.escapefromcraftov.game.plugin.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GameCombatAttributionPolicyTest {

  @Test
  void friendlyDamageDefersClearUntilAnUncancelledPositiveFinalHit() {
    assertEquals(GameCombatAttributionPolicy.FriendlyDamage.CANCEL_AND_KEEP_HOSTILE,
        GameCombatAttributionPolicy.friendlyDamage(false));
    assertEquals(GameCombatAttributionPolicy.FriendlyDamage.ALLOW_AND_DEFER_CLEAR,
        GameCombatAttributionPolicy.friendlyDamage(true));
    org.junit.jupiter.api.Assertions.assertTrue(
        GameCombatAttributionPolicy.clearsHostileAfterEvent(true, false, 1));
    org.junit.jupiter.api.Assertions.assertFalse(
        GameCombatAttributionPolicy.clearsHostileAfterEvent(true, true, 1));
    org.junit.jupiter.api.Assertions.assertFalse(
        GameCombatAttributionPolicy.clearsHostileAfterEvent(true, false, 0));
    org.junit.jupiter.api.Assertions.assertFalse(
        GameCombatAttributionPolicy.clearsHostileAfterEvent(false, false, 1));
  }
}
