package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/** Raised when a loadout claim races with an already durable craftov outcome. */
public final class CraftovInventoryClosedException extends IllegalStateException {

  private final CraftovInventoryOutcome outcome;

  public CraftovInventoryClosedException(CraftovInventoryOutcome outcome) {
    super("Craftov inventory is already settled as " + outcome);
    this.outcome = Preconditions.checkNotNull(outcome, "outcome");
  }

  public CraftovInventoryOutcome outcome() {
    return outcome;
  }
}
