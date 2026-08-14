package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Durable outcome for one player in one craftov. `newlyApplied` is false when an
/// idempotent retry observed a settlement committed by an earlier attempt.
public record CraftovSettlementResult(CraftovInventoryOutcome outcome, boolean newlyApplied) {

  public CraftovSettlementResult {
    Preconditions.checkNotNull(outcome, "outcome");
  }
}
