package com.birditch.escapefromcraftov.market;

import com.birditch.escapefromcraftov.common.check.Preconditions;

public record MarketMutation(MarketListing listing, boolean replayed) {
  public MarketMutation {
    Preconditions.checkNotNull(listing, "listing");
  }
}
