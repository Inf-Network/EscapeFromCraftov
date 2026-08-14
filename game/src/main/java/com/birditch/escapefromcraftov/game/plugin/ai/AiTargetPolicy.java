package com.birditch.escapefromcraftov.game.plugin.ai;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/** Pure fail-closed eligibility policy for a Craftov-owned mob target. */
final class AiTargetPolicy {

  private AiTargetPolicy() {}

  static boolean eligible(Facts facts) {
    Preconditions.checkNotNull(facts, "facts");
    return facts.gameRunning()
        && facts.participant()
        && !facts.extracted()
        && facts.sameInstanceWorld()
        && facts.online()
        && facts.validEntity()
        && !facts.dead()
        && facts.positiveHealth()
        && facts.survivalMode()
        && facts.combatAlive();
  }

  record Facts(
      boolean gameRunning,
      boolean participant,
      boolean extracted,
      boolean sameInstanceWorld,
      boolean online,
      boolean validEntity,
      boolean dead,
      boolean positiveHealth,
      boolean survivalMode,
      boolean combatAlive) {}
}
