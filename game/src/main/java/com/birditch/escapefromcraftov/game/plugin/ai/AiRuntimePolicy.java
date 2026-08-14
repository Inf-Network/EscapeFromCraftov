package com.birditch.escapefromcraftov.game.plugin.ai;

import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.loot.LootTableId;
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

  /** Maps effective zone pressure onto the shared T1-T4 Craftov loot tables. */
  static LootProfile loot(double effectiveDifficulty, RaidMode mode,
      RaidDifficulty difficulty) {
    Preconditions.checkArgument(Double.isFinite(effectiveDifficulty) && effectiveDifficulty >= 0,
        "effectiveDifficulty must be finite and non-negative");
    Preconditions.checkNotNull(mode, "mode");
    Preconditions.checkNotNull(difficulty, "difficulty");
    int tier = Math.clamp((int) Math.ceil(effectiveDifficulty), 1, 4);
    int rolls = Math.addExact(mode.lootRolls(), difficulty.lootBonusRolls());
    return new LootProfile(LootTableId.of("craftov_tier_" + tier), tier, rolls);
  }

  private static double clamp(double value, double minimum, double maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  record DifficultyProfile(
      double healthMultiplier,
      double damageMultiplier,
      double movementMultiplier,
      int equipmentTier) {}

  record LootProfile(LootTableId tableId, int tier, int rolls) {}
}
