package com.birditch.escapefromcraftov.game.plugin.downed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.combat.PlayerCombatState;
import org.junit.jupiter.api.Test;

class DownedFlowPolicyTest {

  @Test
  void firstLethalHitDownsOnlyWhenAnAliveTeammateCanRescue() {
    assertTrue(DownedFlowPolicy.isLethal(5, 5));
    assertFalse(DownedFlowPolicy.isLethal(5, 4.99));
    assertEquals(DownedFlowPolicy.LethalDecision.ENTER_DOWNED,
        DownedFlowPolicy.lethalDecision(true, PlayerCombatState.ALIVE, true, false,
            true, 0, 1));
    assertEquals(DownedFlowPolicy.LethalDecision.PASS_THROUGH,
        DownedFlowPolicy.lethalDecision(true, PlayerCombatState.ALIVE, true, false,
            false, 0, 1));
  }

  @Test
  void secondLethalHitAndHostileFollowUpCannotCreateAnotherDown() {
    assertEquals(DownedFlowPolicy.LethalDecision.PASS_THROUGH,
        DownedFlowPolicy.lethalDecision(true, PlayerCombatState.ALIVE, true, false,
            true, 1, 1));
    assertEquals(DownedFlowPolicy.LethalDecision.FINISH_DOWNED,
        DownedFlowPolicy.lethalDecision(true, PlayerCombatState.DOWNED, true, true,
            true, 1, 1));
    assertEquals(DownedFlowPolicy.LethalDecision.PASS_THROUGH,
        DownedFlowPolicy.lethalDecision(true, PlayerCombatState.DOWNED, true, false,
            true, 1, 1));
  }

  @Test
  void rescueRequiresLiveSameTeamPlayersSneakingWithinThreeBlocks() {
    assertTrue(DownedFlowPolicy.canContinueRescue(true, true, true, true,
        true, true, 9, 9));
    assertFalse(DownedFlowPolicy.canContinueRescue(true, true, true, true,
        false, true, 1, 9));
    assertFalse(DownedFlowPolicy.canContinueRescue(true, true, true, true,
        true, true, 9.01, 9));
  }

  @Test
  void rescueProgressIsClamped() {
    assertEquals(0, DownedFlowPolicy.progress(100, 50, 1000));
    assertEquals(0.5f, DownedFlowPolicy.progress(100, 600, 1000));
    assertEquals(1, DownedFlowPolicy.progress(100, 2000, 1000));
  }
}
