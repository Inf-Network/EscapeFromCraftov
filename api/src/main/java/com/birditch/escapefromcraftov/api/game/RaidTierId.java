package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.regex.Pattern;

/// Stable, map-scoped action-tier identifier carried across matchmaking.
public record RaidTierId(String value) {

  private static final Pattern PATTERN = Pattern.compile("[a-z0-9_]+");

  public RaidTierId {
    Preconditions.checkNotNull(value, "value");
    Preconditions.checkArgument(PATTERN.matcher(value).matches(),
        "RaidTierId must match [a-z0-9_]+ but was: " + value);
  }

  public static RaidTierId of(String value) {
    return new RaidTierId(value);
  }
}
