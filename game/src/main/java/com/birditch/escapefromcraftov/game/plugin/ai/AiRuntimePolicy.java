package com.birditch.escapefromcraftov.game.plugin.ai;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.SplittableRandom;

/** Pure population and difficulty rules used by the Bukkit AI runtime. */
final class AiRuntimePolicy {

  private AiRuntimePolicy() {}

  static int targetPopulation(int minimum, int maximum, long seed) {
    Preconditions.checkArgument(minimum >= 0 && maximum >= minimum,
        "invalid AI population range");
    if (minimum == maximum) return minimum;
    return (int) new SplittableRandom(seed).nextLong(minimum, (long) maximum + 1L);
  }

  static DifficultyProfile difficulty(double value) {
    Preconditions.checkArgument(Double.isFinite(value) && value >= 0,
        "difficulty must be finite and non-negative");
    double health = clamp(0.65 + value * 0.35, 0.65, 3.5);
    double damage = clamp(0.70 + value * 0.30, 0.70, 3.0);
    double movement = clamp(0.85 + value * 0.15, 0.85, 1.35);
    int equipmentTier = Math.clamp((int) Math.floor(value), 0, 3);
    return new DifficultyProfile(health, damage, movement, equipmentTier);
  }

  private static double clamp(double value, double minimum, double maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  record DifficultyProfile(
      double healthMultiplier,
      double damageMultiplier,
      double movementMultiplier,
      int equipmentTier) {}
}
