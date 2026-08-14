package com.birditch.escapefromcraftov.game.plugin;

import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;
import java.util.function.Predicate;

/** Final authority for command-backed or event-backed raid inventory mutations. */
public final class RaidInventoryMutationGate {

  private final CombatService combat;
  private final Predicate<UUID> pending;
  private final Predicate<UUID> settling;

  public RaidInventoryMutationGate(CombatService combat,
      Predicate<UUID> pending, Predicate<UUID> settling) {
    this.combat = Preconditions.checkNotNull(combat, "combat");
    this.pending = Preconditions.checkNotNull(pending, "pending");
    this.settling = Preconditions.checkNotNull(settling, "settling");
  }

  public boolean allows(UUID playerId) {
    UUID checked = Preconditions.checkNotNull(playerId, "playerId");
    return RaidMenuCombatPolicy.alive(combat, checked)
        && !pending.test(checked) && !settling.test(checked);
  }

  /** Compatibility for historical constructors; production must inject the full gate. */
  public static RaidInventoryMutationGate legacy(CombatService combat) {
    return new RaidInventoryMutationGate(combat, _ -> false, _ -> false);
  }

  /** Compatibility for historical constructors that predate CombatService. */
  public static RaidInventoryMutationGate legacyAllowAll() {
    return legacy(RaidMenuCombatPolicy.legacyAlwaysAlive());
  }
}
