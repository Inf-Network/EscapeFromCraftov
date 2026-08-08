package com.birditch.raidcore.game.plugin.command;

import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.config.RaidCoreConfig;
import com.birditch.raidcore.core.module.ModuleManager;
import com.birditch.raidcore.core.service.ServiceRegistry;
import com.birditch.raidcore.game.plugin.RaidGamePlugin;

/// Everything the `/raid` command tree needs at execution time.
public record CommandContext(
    RaidGamePlugin plugin,
    ServiceRegistry services,
    ModuleManager modules,
    Messages messages,
    RaidCoreConfig config) {

  public CommandContext {
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(services, "services");
    Preconditions.checkNotNull(modules, "modules");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(config, "config");
  }
}
