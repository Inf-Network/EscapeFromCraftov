package com.birditch.escapefromcraftov.game.plugin.ownership;

/** Current result of checking a frozen loot claim against one raid member. */
public record LootAccess(Kind kind, int secondsRemaining) {

  public LootAccess {
    if (secondsRemaining < 0) throw new IllegalArgumentException("secondsRemaining must be >= 0");
  }

  public boolean allowed() {
    return kind == Kind.PRIORITY || kind == Kind.PUBLIC;
  }

  public enum Kind {
    PRIORITY,
    PUBLIC,
    PROTECTED,
    NOT_ACCESSIBLE
  }
}
