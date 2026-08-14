package com.birditch.escapefromcraftov.game.plugin.extraction;

import com.birditch.escapefromcraftov.api.extraction.ExtractionCondition;
import com.birditch.escapefromcraftov.api.extraction.ExtractionContext;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/** Pure live gate that never caches a previously completed map-event state. */
public final class EventCompletedExtractionCondition implements ExtractionCondition {

  private final String eventId;
  private final String displayName;
  private final EventCompletionLookup events;

  public EventCompletedExtractionCondition(
      String eventId, String displayName, EventCompletionLookup events) {
    this.eventId = Preconditions.checkNotNull(eventId, "eventId");
    this.displayName = Preconditions.checkNotNull(displayName, "displayName");
    this.events = Preconditions.checkNotNull(events, "events");
  }

  public String eventId() {
    return eventId;
  }

  public String displayName() {
    return displayName;
  }

  @Override
  public String describe() {
    return "需完成 " + displayName;
  }

  @Override
  public boolean isSatisfied(ExtractionContext context) {
    Preconditions.checkNotNull(context, "context");
    return events.completed(context.gameId(), eventId);
  }
}
