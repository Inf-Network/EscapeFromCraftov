package com.birditch.raidcore.game;

import com.birditch.raidcore.api.event.EventBus;
import com.birditch.raidcore.api.event.game.PlayerJoinedRaidEvent;
import com.birditch.raidcore.api.event.game.PlayerLeftRaidEvent;
import com.birditch.raidcore.api.event.game.RaidCreatedEvent;
import com.birditch.raidcore.api.event.game.RaidEndedEvent;
import com.birditch.raidcore.api.event.game.RaidStartedEvent;
import java.util.ArrayList;
import java.util.List;

/// Records every game lifecycle event published on the bus, in publication
/// order, for test assertions.
final class RecordedGameEvents {

    final List<RaidCreatedEvent> created = new ArrayList<>();
    final List<PlayerJoinedRaidEvent> joined = new ArrayList<>();
    final List<PlayerLeftRaidEvent> left = new ArrayList<>();
    final List<RaidStartedEvent> started = new ArrayList<>();
    final List<RaidEndedEvent> ended = new ArrayList<>();

    RecordedGameEvents(EventBus eventBus) {
        eventBus.subscribe(RaidCreatedEvent.class, created::add);
        eventBus.subscribe(PlayerJoinedRaidEvent.class, joined::add);
        eventBus.subscribe(PlayerLeftRaidEvent.class, left::add);
        eventBus.subscribe(RaidStartedEvent.class, started::add);
        eventBus.subscribe(RaidEndedEvent.class, ended::add);
    }
}
