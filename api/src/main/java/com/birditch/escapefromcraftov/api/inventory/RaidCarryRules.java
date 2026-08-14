package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/**
 * Server-configurable carry tuning consumed by the core authority.
 *
 * <p>The defaults are conservative development values, not claims about an
 * upstream game's unpublished movement thresholds.</p>
 */
public record RaidCarryRules(
    int pocketSlots,
    int maximumContainerSlots,
    long baseMaximumWeightGrams,
    long weightPerContainerSlotGrams,
    long legacyMaximumWeightGrams,
    double comfortableWeightFraction,
    double minimumMovementMultiplier) {

  public RaidCarryRules {
    Preconditions.checkArgument(pocketSlots >= 0 && pocketSlots <= 9,
        "pocketSlots must be between 0 and 9");
    Preconditions.checkArgument(maximumContainerSlots >= 0 && maximumContainerSlots <= 27,
        "maximumContainerSlots must be between 0 and 27");
    Preconditions.checkArgument(baseMaximumWeightGrams > 0,
        "baseMaximumWeightGrams must be positive");
    Preconditions.checkArgument(weightPerContainerSlotGrams >= 0,
        "weightPerContainerSlotGrams must not be negative");
    Preconditions.checkArgument(legacyMaximumWeightGrams >= baseMaximumWeightGrams,
        "legacyMaximumWeightGrams must cover the base limit");
    Preconditions.checkArgument(comfortableWeightFraction > 0.0
            && comfortableWeightFraction < 1.0,
        "comfortableWeightFraction must be between 0 and 1");
    Preconditions.checkArgument(minimumMovementMultiplier > 0.0
            && minimumMovementMultiplier <= 1.0,
        "minimumMovementMultiplier must be in (0, 1]");
  }

  public static RaidCarryRules defaults() {
    return new RaidCarryRules(4, 27, 12_000L, 2_000L, 60_000L, 0.65, 0.70);
  }
}
