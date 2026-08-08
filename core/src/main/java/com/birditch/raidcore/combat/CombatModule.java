package com.birditch.raidcore.combat;

import com.birditch.raidcore.api.combat.CombatService;
import com.birditch.raidcore.api.combat.DownedService;
import com.birditch.raidcore.core.RaidContext;
import com.birditch.raidcore.core.module.RaidModule;

/// Wires the combat domain: registers [CombatService] and [DownedService].
///
/// [com.birditch.raidcore.api.combat.DamageService] and
/// [com.birditch.raidcore.api.combat.MedicalService] are contract-only in v1
/// and intentionally have no implementation or registration here.
public final class CombatModule implements RaidModule {

  public CombatModule() {
  }

  @Override
  public String id() {
    return "combat";
  }

  @Override
  public void initialize(RaidContext context) {
    DefaultCombatService combatService = new DefaultCombatService();
    DefaultDownedService downedService =
        new DefaultDownedService(combatService, context.eventBus());
    context.services().register(CombatService.class, combatService);
    context.services().register(DownedService.class, downedService);
  }

  /// Combat services are passive; nothing to activate.
  @Override
  public void start() {
  }

  /// Combat services hold no external resources; nothing to release.
  @Override
  public void stop() {
  }
}
