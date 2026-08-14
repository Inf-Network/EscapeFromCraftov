package com.birditch.escapefromcraftov.game.plugin.deathcrate;

import com.birditch.escapefromcraftov.api.combat.PlayerCombatState;
import com.birditch.escapefromcraftov.game.plugin.ownership.LootAccess;

/** Pure final gate layered over room/claim access for death-crate actions. */
final class DeathCrateAccessPolicy {

  private DeathCrateAccessPolicy() {}

  static LootAccess filter(PlayerCombatState combatState, LootAccess access) {
    return combatState == PlayerCombatState.ALIVE
        ? access : new LootAccess(LootAccess.Kind.NOT_ACCESSIBLE, 0);
  }
}
