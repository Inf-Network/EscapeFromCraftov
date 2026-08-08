package com.birditch.raidcore.game;

import com.birditch.raidcore.api.event.EventBus;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.random.RandomProvider;
import com.birditch.raidcore.common.time.TimeSource;
import com.birditch.raidcore.core.config.RaidCoreConfig;
import java.util.logging.Logger;

/// Constructor dependencies of [RaidGameManager], bundled so the manager keeps
/// a single-parameter constructor.
public record GameManagerDeps(SessionService sessions, EventBus eventBus, RaidScheduler scheduler,
                              RandomProvider random, TimeSource timeSource,
                              RaidCoreConfig.GameSettings settings,
                              RaidCoreConfig.ExtractionSettings extraction, Logger logger) {

    public GameManagerDeps {
        Preconditions.checkNotNull(sessions, "sessions");
        Preconditions.checkNotNull(eventBus, "eventBus");
        Preconditions.checkNotNull(scheduler, "scheduler");
        Preconditions.checkNotNull(random, "random");
        Preconditions.checkNotNull(timeSource, "timeSource");
        Preconditions.checkNotNull(settings, "settings");
        Preconditions.checkNotNull(extraction, "extraction");
        Preconditions.checkNotNull(logger, "logger");
    }
}
