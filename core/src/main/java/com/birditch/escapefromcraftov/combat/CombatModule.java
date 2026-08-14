package com.birditch.escapefromcraftov.combat;

import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.api.combat.DownedService;
import com.birditch.escapefromcraftov.core.CraftovContext;
import com.birditch.escapefromcraftov.core.module.CraftovModule;

/// Wires the combat domain: registers [CombatService] and [DownedService].
///
/// [com.birditch.escapefromcraftov.api.combat.DamageService] and
/// [com.birditch.escapefromcraftov.api.combat.MedicalService] are contract-only in v1
/// and intentionally have no implementation or registration here.
public final class CombatModule implements CraftovModule {

  public CombatModule() {
  }

  @Override
  public String id() {
    return "combat";
  }

  @Override
  public void initialize(CraftovContext context) {
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
