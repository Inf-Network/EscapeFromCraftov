package com.birditch.escapefromcraftov.game.plugin.extraction;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/**
 * Stable facade created before map providers and bound after the event runtime exists.
 * Unbound, stopped and closed application phases fail closed.
 */
public final class BindableEventCompletionLookup implements EventCompletionLookup {

  private volatile EventCompletionLookup delegate = DENY_ALL;

  public void bind(EventCompletionLookup delegate) {
    EventCompletionLookup value = Preconditions.checkNotNull(delegate, "delegate");
    Preconditions.checkArgument(value != this, "delegate must not be this lookup");
    this.delegate = value;
  }

  public void unbind() {
    delegate = DENY_ALL;
  }

  @Override
  public boolean completed(CraftovGameId gameId, String eventId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(eventId, "eventId");
    return delegate.completed(gameId, eventId);
  }
}
