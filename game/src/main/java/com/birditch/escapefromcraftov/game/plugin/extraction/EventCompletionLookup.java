package com.birditch.escapefromcraftov.game.plugin.extraction;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;

/** Authoritative, per-game lookup used by live extraction conditions. */
@FunctionalInterface
public interface EventCompletionLookup {

  EventCompletionLookup DENY_ALL = (_, _) -> false;

  /** True only while this exact game's authored event is in COMPLETED state. */
  boolean completed(CraftovGameId gameId, String eventId);
}
