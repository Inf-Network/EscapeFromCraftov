package com.birditch.escapefromcraftov.game.plugin.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AiTargetPolicyTest {

  @Test
  void onlyAliveSurvivalParticipantsInTheRunningInstanceAreEligible() {
    assertTrue(AiTargetPolicy.eligible(facts(true, true, false,
        true, true, true, false, true, true, true)));
    assertFalse(AiTargetPolicy.eligible(facts(false, true, false,
        true, true, true, false, true, true, true)));
    assertFalse(AiTargetPolicy.eligible(facts(true, false, false,
        true, true, true, false, true, true, true)));
    assertFalse(AiTargetPolicy.eligible(facts(true, true, true,
        true, true, true, false, true, true, true)));
    assertFalse(AiTargetPolicy.eligible(facts(true, true, false,
        false, true, true, false, true, true, true)));
    assertFalse(AiTargetPolicy.eligible(facts(true, true, false,
        true, false, true, false, true, true, true)));
    assertFalse(AiTargetPolicy.eligible(facts(true, true, false,
        true, true, true, true, false, true, true)));
    assertFalse(AiTargetPolicy.eligible(facts(true, true, false,
        true, true, true, false, true, false, true)));
    assertFalse(AiTargetPolicy.eligible(facts(true, true, false,
        true, true, true, false, true, true, false)));
  }

  private static AiTargetPolicy.Facts facts(boolean gameRunning, boolean participant,
      boolean extracted, boolean sameInstanceWorld, boolean online, boolean validEntity,
      boolean dead, boolean positiveHealth, boolean survivalMode, boolean combatAlive) {
    return new AiTargetPolicy.Facts(gameRunning, participant, extracted, sameInstanceWorld,
        online, validEntity, dead, positiveHealth, survivalMode, combatAlive);
  }
}
