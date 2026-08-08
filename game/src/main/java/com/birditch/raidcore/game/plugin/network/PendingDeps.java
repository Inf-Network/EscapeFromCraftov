package com.birditch.raidcore.game.plugin.network;

import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.network.AssignmentStore;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.bukkit.BungeeConnector;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.game.plugin.GameServerConfig;
import java.util.logging.Logger;

/// Constructor dependencies of [PendingJoinHandler], bundled so the handler
/// keeps a single-parameter constructor.
public record PendingDeps(AssignmentStore store, RaidGameService games, GameServerConfig config,
                          BungeeConnector bungee, RaidScheduler scheduler, Messages messages,
                          Logger logger) {

  public PendingDeps {
    Preconditions.checkNotNull(store, "store");
    Preconditions.checkNotNull(games, "games");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(bungee, "bungee");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(logger, "logger");
  }
}
