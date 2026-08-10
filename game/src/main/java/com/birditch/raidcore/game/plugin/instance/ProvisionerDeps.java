package com.birditch.raidcore.game.plugin.instance;

import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.config.RaidCoreConfig;
import com.birditch.raidcore.game.plugin.GameServerConfig;
import java.util.logging.Logger;

/// Constructor dependencies of [InstanceProvisioner], bundled so the
/// provisioner keeps a single-parameter constructor. `coreSettings` carries
/// the instance cap; `config` carries the min-ready target and this server's
/// identity.
public record ProvisionerDeps(
    RaidGameService games,
    RaidCoreConfig.GameSettings coreSettings,
    GameServerConfig config,
    MapAvailability maps,
    RaidScheduler scheduler,
    Logger logger) {

  public ProvisionerDeps(RaidGameService games, RaidCoreConfig.GameSettings coreSettings,
      GameServerConfig config, RaidScheduler scheduler, Logger logger) {
    this(games, coreSettings, config, _ -> true, scheduler, logger);
  }

  public ProvisionerDeps {
    Preconditions.checkNotNull(games, "games");
    Preconditions.checkNotNull(coreSettings, "coreSettings");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(maps, "maps");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(logger, "logger");
  }
}
