package com.birditch.escapefromcraftov.game.plugin.ai;

/** Prevents replacing a persistent AI entity while its chunk is serialized. */
final class AiEntityPresencePolicy {

  private AiEntityPresencePolicy() {}

  static boolean reserveIdentity(boolean indexedAsMob, boolean entityValid,
      boolean lastKnownChunkLoaded) {
    return !lastKnownChunkLoaded && (!indexedAsMob || !entityValid);
  }
}
