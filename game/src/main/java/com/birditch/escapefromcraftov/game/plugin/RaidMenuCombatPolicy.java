package com.birditch.escapefromcraftov.game.plugin;

import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.api.combat.PlayerCombatState;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/** Authoritative combat-state gate shared by command-backed raid menus. */
public final class RaidMenuCombatPolicy {

  private static final CombatService LEGACY_ALWAYS_ALIVE = new CombatService() {
    @Override
    public PlayerCombatState stateOf(UUID playerId) {
      Preconditions.checkNotNull(playerId, "playerId");
      return PlayerCombatState.ALIVE;
    }

    @Override
    public void setState(UUID playerId, PlayerCombatState state) {}

    @Override
    public void reset(UUID playerId) {}
  };

  private RaidMenuCombatPolicy() {}

  public static boolean alive(CombatService combat, UUID playerId) {
    return Preconditions.checkNotNull(combat, "combat").stateOf(
        Preconditions.checkNotNull(playerId, "playerId")) == PlayerCombatState.ALIVE;
  }

  /** Compatibility only; production wiring must inject the registered CombatService. */
  public static CombatService legacyAlwaysAlive() {
    return LEGACY_ALWAYS_ALIVE;
  }
}
