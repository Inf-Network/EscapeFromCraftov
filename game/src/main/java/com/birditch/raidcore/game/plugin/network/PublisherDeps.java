package com.birditch.raidcore.game.plugin.network;

import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.network.InstanceDirectory;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.time.TimeSource;
import com.birditch.raidcore.core.config.RaidCoreConfig;
import com.birditch.raidcore.game.plugin.GameServerConfig;
import com.birditch.raidcore.game.plugin.instance.InstanceWorldIndex;
import java.util.logging.Logger;

/// Constructor dependencies of [SnapshotPublisher], bundled so the publisher
/// keeps a single-parameter constructor.
public record PublisherDeps(RaidGameService games, InstanceWorldIndex index,
                            InstanceDirectory directory, GameServerConfig config,
                            RaidCoreConfig coreConfig, TimeSource timeSource,
                            RaidScheduler scheduler, Logger logger) {

  public PublisherDeps {
    Preconditions.checkNotNull(games, "games");
    Preconditions.checkNotNull(index, "index");
    Preconditions.checkNotNull(directory, "directory");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(coreConfig, "coreConfig");
    Preconditions.checkNotNull(timeSource, "timeSource");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(logger, "logger");
  }
}
