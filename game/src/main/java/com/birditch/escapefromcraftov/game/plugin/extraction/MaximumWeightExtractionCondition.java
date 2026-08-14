package com.birditch.escapefromcraftov.game.plugin.extraction;

import com.birditch.escapefromcraftov.api.extraction.ExtractionCondition;
import com.birditch.escapefromcraftov.api.extraction.ExtractionContext;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/** Rechecks live carried weight both before and after the extraction countdown. */
public final class MaximumWeightExtractionCondition implements ExtractionCondition {

  private final long maximumGrams;
  private final CarriedInventoryLookup inventories;

  public MaximumWeightExtractionCondition(long maximumGrams, CarriedInventoryLookup inventories) {
    Preconditions.checkArgument(maximumGrams >= 0, "maximumGrams must not be negative");
    this.maximumGrams = maximumGrams;
    this.inventories = Preconditions.checkNotNull(inventories, "inventories");
  }

  public long maximumGrams() {
    return maximumGrams;
  }

  @Override
  public String describe() {
    return "携带总重量不得超过 " + formatWeight(maximumGrams);
  }

  @Override
  public boolean isSatisfied(ExtractionContext context) {
    Preconditions.checkNotNull(context, "context");
    return inventories.find(context.playerId())
        .map(state -> state.weightGrams() <= maximumGrams).orElse(false);
  }

  private static String formatWeight(long grams) {
    if (grams < 1_000) return grams + "g";
    return String.format(java.util.Locale.ROOT, "%.1fkg", grams / 1_000.0);
  }
}
