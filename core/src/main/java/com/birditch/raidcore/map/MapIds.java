package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;

public final class MapIds {

  private MapIds() {}

  public static String requireValid(String value, String name) {
    Preconditions.checkNotNull(value, name);
    Preconditions.checkArgument(value.matches("[a-z0-9_]+"),
        name + " must match [a-z0-9_]+ but was: " + value);
    return value;
  }
}
