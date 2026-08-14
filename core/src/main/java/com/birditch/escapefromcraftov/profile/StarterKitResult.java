package com.birditch.escapefromcraftov.profile;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Result of the first-profile transaction.
public record StarterKitResult(boolean granted, Money balanceAfter) {

  public StarterKitResult {
    Preconditions.checkNotNull(balanceAfter, "balanceAfter");
  }
}
