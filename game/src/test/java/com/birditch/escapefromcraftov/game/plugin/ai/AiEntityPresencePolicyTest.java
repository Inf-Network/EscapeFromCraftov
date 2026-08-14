package com.birditch.escapefromcraftov.game.plugin.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiEntityPresencePolicyTest {

  @Test
  void invalidIndexedBossKeepsItsIdentityWhileLastChunkIsUnloaded() {
    assertTrue(AiEntityPresencePolicy.reserveIdentity(true, false, false));
    assertFalse(AiEntityPresencePolicy.reserveIdentity(true, false, true));
  }

  @Test
  void absentEntityUsesTheSameNoDuplicateRule() {
    assertTrue(AiEntityPresencePolicy.reserveIdentity(false, false, false));
    assertFalse(AiEntityPresencePolicy.reserveIdentity(false, false, true));
    assertFalse(AiEntityPresencePolicy.reserveIdentity(true, true, false));
  }
}
