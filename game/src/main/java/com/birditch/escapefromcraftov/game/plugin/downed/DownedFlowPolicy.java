package com.birditch.escapefromcraftov.game.plugin.downed;

import com.birditch.escapefromcraftov.api.combat.PlayerCombatState;

/** Pure decisions for the Paper down/revive listener. */
final class DownedFlowPolicy {

  enum LethalDecision {
    PASS_THROUGH,
    ENTER_DOWNED,
    FINISH_DOWNED
  }

  private DownedFlowPolicy() {}

  static boolean isLethal(double health, double finalDamage) {
    // Bukkit's final damage already includes its ABSORPTION modifier.
    return finalDamage > 0 && finalDamage >= Math.max(0, health);
  }

  static LethalDecision lethalDecision(boolean activeParticipant,
      PlayerCombatState combatState, boolean lethal, boolean hostileFollowUp,
      boolean hasAliveTeammate, int previousDowns, int maximumDowns) {
    if (!activeParticipant || combatState == PlayerCombatState.DEAD || !lethal) {
      return LethalDecision.PASS_THROUGH;
    }
    if (combatState == PlayerCombatState.DOWNED) {
      return hostileFollowUp ? LethalDecision.FINISH_DOWNED : LethalDecision.PASS_THROUGH;
    }
    return hasAliveTeammate && previousDowns < maximumDowns
        ? LethalDecision.ENTER_DOWNED : LethalDecision.PASS_THROUGH;
  }

  static boolean canContinueRescue(boolean activeParticipants, boolean sameTeam,
      boolean rescuerAlive, boolean targetDowned, boolean rescuerSneaking,
      boolean sameWorld, double distanceSquared, double maximumDistanceSquared) {
    return activeParticipants && sameTeam && rescuerAlive && targetDowned
        && rescuerSneaking && sameWorld && distanceSquared <= maximumDistanceSquared;
  }

  static float progress(long startedAtNanos, long nowNanos, long durationNanos) {
    if (durationNanos <= 0) return 1;
    long elapsed = Math.max(0, nowNanos - startedAtNanos);
    return (float) Math.min(1, elapsed / (double) durationNanos);
  }
}
