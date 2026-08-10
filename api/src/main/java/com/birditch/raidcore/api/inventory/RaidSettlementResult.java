package com.birditch.raidcore.api.inventory;

import com.birditch.raidcore.common.check.Preconditions;

/// Durable outcome for one player in one raid. `newlyApplied` is false when an
/// idempotent retry observed a settlement committed by an earlier attempt.
public record RaidSettlementResult(RaidInventoryOutcome outcome, boolean newlyApplied) {

  public RaidSettlementResult {
    Preconditions.checkNotNull(outcome, "outcome");
  }
}
