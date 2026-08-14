package com.birditch.escapefromcraftov.game;

import com.birditch.escapefromcraftov.api.event.EventBus;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.session.SessionService;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.random.RandomProvider;
import com.birditch.escapefromcraftov.common.time.TimeSource;
import com.birditch.escapefromcraftov.core.config.EscapeFromCraftovConfig;
import java.util.logging.Logger;

/// Constructor dependencies of [CraftovGameManager], bundled so the manager keeps
/// a single-parameter constructor.
public record GameManagerDeps(SessionService sessions, EventBus eventBus, CraftovScheduler scheduler,
                              RandomProvider random, TimeSource timeSource,
                              EscapeFromCraftovConfig.GameSettings settings,
                              EscapeFromCraftovConfig.ExtractionSettings extraction,
                              CraftovMapProvider maps, Logger logger) {

    public GameManagerDeps(SessionService sessions, EventBus eventBus, CraftovScheduler scheduler,
                           RandomProvider random, TimeSource timeSource,
                           EscapeFromCraftovConfig.GameSettings settings,
                           EscapeFromCraftovConfig.ExtractionSettings extraction, Logger logger) {
        this(sessions, eventBus, scheduler, random, timeSource, settings, extraction,
                CraftovMapProvider.EMPTY, logger);
    }

    public GameManagerDeps {
        Preconditions.checkNotNull(sessions, "sessions");
        Preconditions.checkNotNull(eventBus, "eventBus");
        Preconditions.checkNotNull(scheduler, "scheduler");
        Preconditions.checkNotNull(random, "random");
        Preconditions.checkNotNull(timeSource, "timeSource");
        Preconditions.checkNotNull(settings, "settings");
        Preconditions.checkNotNull(extraction, "extraction");
        Preconditions.checkNotNull(maps, "maps");
        Preconditions.checkNotNull(logger, "logger");
    }
}
