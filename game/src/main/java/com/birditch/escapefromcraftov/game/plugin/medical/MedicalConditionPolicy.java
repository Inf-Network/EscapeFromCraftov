package com.birditch.escapefromcraftov.game.plugin.medical;

import java.util.EnumSet;
import java.util.Set;

/** Deterministic trauma and progress decisions, deliberately independent of Bukkit. */
final class MedicalConditionPolicy {

  enum TraumaSource {
    MELEE_OR_PROJECTILE,
    FALL,
    EXPLOSION,
    FIRE,
    ENVIRONMENT
  }

  private MedicalConditionPolicy() {}

  static Set<MedicalCondition> conditions(double finalDamage, double maximumHealth,
      TraumaSource source) {
    if (finalDamage <= 0 || maximumHealth <= 0) return Set.of();
    double ratio = finalDamage / maximumHealth;
    EnumSet<MedicalCondition> result = EnumSet.noneOf(MedicalCondition.class);

    if (ratio >= 0.15) result.add(MedicalCondition.PAIN);
    if (source == TraumaSource.MELEE_OR_PROJECTILE && ratio >= 0.20
        || source != TraumaSource.FIRE && ratio >= 0.28) {
      result.add(MedicalCondition.BLEEDING);
    }
    if ((source == TraumaSource.FALL || source == TraumaSource.EXPLOSION) && ratio >= 0.22
        || source != TraumaSource.FIRE && ratio >= 0.40) {
      result.add(MedicalCondition.FRACTURE);
    }
    return Set.copyOf(result);
  }

  static float progress(long startedAtNanos, long nowNanos, long durationNanos) {
    if (durationNanos <= 0) return 1;
    long elapsed = Math.max(0, nowNanos - startedAtNanos);
    return (float) Math.min(1, elapsed / (double) durationNanos);
  }
}
