package com.birditch.escapefromcraftov.game.plugin.deathcrate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.birditch.escapefromcraftov.api.combat.PlayerCombatState;
import com.birditch.escapefromcraftov.game.plugin.ownership.LootAccess;
import org.junit.jupiter.api.Test;

class DeathCrateAccessPolicyTest {

  @Test
  void downedAndDeadPlayersCannotUseAnOtherwiseAuthorizedCrate() {
    LootAccess priority = new LootAccess(LootAccess.Kind.PRIORITY, 20);

    assertEquals(LootAccess.Kind.PRIORITY,
        DeathCrateAccessPolicy.filter(PlayerCombatState.ALIVE, priority).kind());
    assertEquals(LootAccess.Kind.NOT_ACCESSIBLE,
        DeathCrateAccessPolicy.filter(PlayerCombatState.DOWNED, priority).kind());
    assertEquals(LootAccess.Kind.NOT_ACCESSIBLE,
        DeathCrateAccessPolicy.filter(PlayerCombatState.DEAD, priority).kind());
  }
}
