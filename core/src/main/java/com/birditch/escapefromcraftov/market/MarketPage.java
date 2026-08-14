package com.birditch.escapefromcraftov.market;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;

public record MarketPage(List<MarketListing> listings, int page, int totalPages, long totalListings) {

  public MarketPage {
    listings = List.copyOf(Preconditions.checkNotNull(listings, "listings"));
    Preconditions.checkArgument(page >= 0, "page must not be negative");
    Preconditions.checkArgument(totalPages >= 1, "totalPages must be positive");
    Preconditions.checkArgument(totalListings >= 0, "totalListings must not be negative");
  }
}
