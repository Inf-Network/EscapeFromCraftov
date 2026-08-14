package com.birditch.escapefromcraftov.game.plugin.ai;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Pure validation and idempotent damage rules for AI-owned projectiles. */
final class AiProjectilePolicy {

  private AiProjectilePolicy() {}

  static Optional<Ownership> ownership(@Nullable String gameId, @Nullable String zoneId,
      @Nullable Double damageMultiplier) {
    if (gameId == null || gameId.isBlank() || zoneId == null || zoneId.isBlank()
        || damageMultiplier == null || !Double.isFinite(damageMultiplier)
        || damageMultiplier <= 0) {
      return Optional.empty();
    }
    return Optional.of(new Ownership(gameId, zoneId, damageMultiplier));
  }

  static boolean eligible(HitFacts facts) {
    return facts.runtimeActive()
        && facts.sameInstanceWorld()
        && facts.targetEligible()
        && facts.projectileInsideLeash()
        && facts.targetInsideLeash();
  }

  /**
   * Freezes the first unscaled event damage on the projectile. Replaying the
   * handler, or a piercing projectile hitting again, therefore never compounds
   * an already-scaled value.
   */
  static Optional<ScaledDamage> scale(double eventDamage, @Nullable Double storedBaseDamage,
      double multiplier) {
    double baseDamage = storedBaseDamage == null ? eventDamage : storedBaseDamage;
    if (!Double.isFinite(baseDamage) || baseDamage < 0
        || !Double.isFinite(multiplier) || multiplier <= 0) {
      return Optional.empty();
    }
    double scaledDamage = baseDamage * multiplier;
    if (!Double.isFinite(scaledDamage) || scaledDamage < 0) return Optional.empty();
    return Optional.of(new ScaledDamage(baseDamage, scaledDamage));
  }

  record Ownership(String gameId, String zoneId, double damageMultiplier) {}

  record HitFacts(
      boolean runtimeActive,
      boolean sameInstanceWorld,
      boolean targetEligible,
      boolean projectileInsideLeash,
      boolean targetInsideLeash) {}

  record ScaledDamage(double baseDamage, double damage) {}
}
