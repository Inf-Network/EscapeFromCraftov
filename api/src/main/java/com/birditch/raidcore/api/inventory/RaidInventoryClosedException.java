package com.birditch.raidcore.api.inventory;

import com.birditch.raidcore.common.check.Preconditions;

/** Raised when a loadout claim races with an already durable raid outcome. */
public final class RaidInventoryClosedException extends IllegalStateException {

  private final RaidInventoryOutcome outcome;

  public RaidInventoryClosedException(RaidInventoryOutcome outcome) {
    super("Raid inventory is already settled as " + outcome);
    this.outcome = Preconditions.checkNotNull(outcome, "outcome");
  }

  public RaidInventoryOutcome outcome() {
    return outcome;
  }
}
