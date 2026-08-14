package com.birditch.escapefromcraftov.game;

import com.birditch.escapefromcraftov.api.event.EventBus;
import com.birditch.escapefromcraftov.api.event.game.PlayerJoinedCraftovEvent;
import com.birditch.escapefromcraftov.api.event.game.PlayerLeftCraftovEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovCreatedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovStartedEvent;
import java.util.ArrayList;
import java.util.List;

/// Records every game lifecycle event published on the bus, in publication
/// order, for test assertions.
final class RecordedGameEvents {

    final List<CraftovCreatedEvent> created = new ArrayList<>();
    final List<PlayerJoinedCraftovEvent> joined = new ArrayList<>();
    final List<PlayerLeftCraftovEvent> left = new ArrayList<>();
    final List<CraftovStartedEvent> started = new ArrayList<>();
    final List<CraftovEndedEvent> ended = new ArrayList<>();

    RecordedGameEvents(EventBus eventBus) {
        eventBus.subscribe(CraftovCreatedEvent.class, created::add);
        eventBus.subscribe(PlayerJoinedCraftovEvent.class, joined::add);
        eventBus.subscribe(PlayerLeftCraftovEvent.class, left::add);
        eventBus.subscribe(CraftovStartedEvent.class, started::add);
        eventBus.subscribe(CraftovEndedEvent.class, ended::add);
    }
}
