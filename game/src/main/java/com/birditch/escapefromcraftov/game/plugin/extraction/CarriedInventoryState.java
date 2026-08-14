package com.birditch.escapefromcraftov.game.plugin.extraction;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/** Lightweight live inventory facts used by extraction conditions. */
public record CarriedInventoryState(long weightGrams, boolean hasBackpack) {

  public CarriedInventoryState {
    Preconditions.checkArgument(weightGrams >= 0, "weightGrams must not be negative");
  }
}
