package com.birditch.escapefromcraftov.game.plugin.network;

import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.network.InstanceDirectory;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.time.TimeSource;
import com.birditch.escapefromcraftov.core.config.EscapeFromCraftovConfig;
import com.birditch.escapefromcraftov.game.plugin.GameServerConfig;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import java.util.logging.Logger;

/// Constructor dependencies of [SnapshotPublisher], bundled so the publisher
/// keeps a single-parameter constructor.
public record PublisherDeps(CraftovGameService games, InstanceWorldIndex index,
                            InstanceDirectory directory, GameServerConfig config,
                            EscapeFromCraftovConfig coreConfig, TimeSource timeSource,
                            CraftovScheduler scheduler, Logger logger) {

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
