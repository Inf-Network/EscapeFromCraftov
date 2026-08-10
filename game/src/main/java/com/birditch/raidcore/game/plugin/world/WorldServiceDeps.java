package com.birditch.raidcore.game.plugin.world;

import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.common.check.Preconditions;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;
import com.birditch.raidcore.game.plugin.editor.ManualMapRegistry;
import org.bukkit.plugin.Plugin;

/// Constructor dependencies of [WorldService], bundled so the service keeps a
/// single-parameter constructor. `templateRoot` is the directory containing
/// one map-template folder per [com.birditch.raidcore.api.game.RaidMapId]
/// value.
public record WorldServiceDeps(
    Plugin plugin,
    RaidScheduler scheduler,
    ExecutorService ioExecutor,
    Path templateRoot,
    boolean allowFlatFallback,
    ManualMapRegistry maps,
    Logger logger) {

  public WorldServiceDeps {
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(ioExecutor, "ioExecutor");
    Preconditions.checkNotNull(templateRoot, "templateRoot");
    Preconditions.checkNotNull(maps, "maps");
    Preconditions.checkNotNull(logger, "logger");
  }
}
