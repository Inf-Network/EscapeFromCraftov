package com.birditch.raidcore.core;

import com.birditch.raidcore.api.event.EventBus;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.config.RaidCoreConfig;
import com.birditch.raidcore.core.database.Database;
import com.birditch.raidcore.core.database.DatabaseConfig;
import com.birditch.raidcore.core.service.ServiceRegistry;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/// Immutable wiring handed to every module during initialization.
///
/// `ioExecutor` is the only executor allowed to run repository IO (virtual threads);
/// callbacks touching domain state must hop back via [RaidScheduler#runSync].
/// `database` is the shared PostgreSQL connection pool — the game server hard-requires
/// it and refuses to start without a reachable database.
public record RaidContext(
    RaidCoreConfig config,
    DatabaseConfig databaseConfig,
    ServiceRegistry services,
    EventBus eventBus,
    RaidScheduler scheduler,
    ExecutorService ioExecutor,
    Database database,
    Logger logger) {

  public RaidContext {
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(databaseConfig, "databaseConfig");
    Preconditions.checkNotNull(services, "services");
    Preconditions.checkNotNull(eventBus, "eventBus");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(ioExecutor, "ioExecutor");
    Preconditions.checkNotNull(database, "database");
    Preconditions.checkNotNull(logger, "logger");
  }
}
