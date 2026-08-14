package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/** Authoritative cargo and weight limits fixed for one raid entry. */
public record RaidCarryCapacity(
    int pocketSlots,
    int containerSlots,
    long comfortableWeightGrams,
    long maximumWeightGrams,
    boolean legacyCompatibility) {

  public RaidCarryCapacity {
    Preconditions.checkArgument(pocketSlots >= 0 && pocketSlots <= 9,
        "pocketSlots must be between 0 and 9");
    Preconditions.checkArgument(containerSlots >= 0 && containerSlots <= 27,
        "containerSlots must be between 0 and 27");
    Preconditions.checkArgument(comfortableWeightGrams >= 0,
        "comfortableWeightGrams must not be negative");
    Preconditions.checkArgument(maximumWeightGrams > 0
            && comfortableWeightGrams < maximumWeightGrams,
        "maximumWeightGrams must exceed comfortableWeightGrams");
  }

  public int cargoSlots() {
    return Math.addExact(pocketSlots, containerSlots);
  }
}
