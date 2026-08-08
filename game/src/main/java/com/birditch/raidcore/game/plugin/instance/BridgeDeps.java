package com.birditch.raidcore.game.plugin.instance;

import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.bukkit.BungeeConnector;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.game.plugin.GameServerConfig;
import com.birditch.raidcore.game.plugin.isolation.VisibilityService;
import com.birditch.raidcore.game.plugin.world.WorldService;
import java.util.logging.Logger;

/// Constructor dependencies of [InstanceWorldBridge], bundled so the bridge
/// keeps a single-parameter constructor.
public record BridgeDeps(
    RaidGameService games,
    WorldService worlds,
    InstanceWorldIndex index,
    VisibilityService visibility,
    BungeeConnector bungee,
    GameServerConfig config,
    RaidScheduler scheduler,
    Logger logger) {

  public BridgeDeps {
    Preconditions.checkNotNull(games, "games");
    Preconditions.checkNotNull(worlds, "worlds");
    Preconditions.checkNotNull(index, "index");
    Preconditions.checkNotNull(visibility, "visibility");
    Preconditions.checkNotNull(bungee, "bungee");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(logger, "logger");
  }
}
