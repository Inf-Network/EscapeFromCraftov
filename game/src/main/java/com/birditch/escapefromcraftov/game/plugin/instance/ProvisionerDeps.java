package com.birditch.escapefromcraftov.game.plugin.instance;

import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.config.EscapeFromCraftovConfig;
import com.birditch.escapefromcraftov.game.plugin.GameServerConfig;
import java.util.logging.Logger;

/// Constructor dependencies of [InstanceProvisioner], bundled so the
/// provisioner keeps a single-parameter constructor. `coreSettings` carries
/// the instance cap; `config` carries the min-ready target and this server's
/// identity.
public record ProvisionerDeps(
    CraftovGameService games,
    EscapeFromCraftovConfig.GameSettings coreSettings,
    GameServerConfig config,
    MapAvailability maps,
    CraftovScheduler scheduler,
    Logger logger) {

  public ProvisionerDeps(CraftovGameService games, EscapeFromCraftovConfig.GameSettings coreSettings,
      GameServerConfig config, CraftovScheduler scheduler, Logger logger) {
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
