package com.birditch.escapefromcraftov.core;

import com.birditch.escapefromcraftov.api.event.EventBus;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.config.EscapeFromCraftovConfig;
import com.birditch.escapefromcraftov.core.database.Database;
import com.birditch.escapefromcraftov.core.database.DatabaseConfig;
import com.birditch.escapefromcraftov.core.service.ServiceRegistry;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/// Immutable wiring handed to every module during initialization.
///
/// `ioExecutor` is the only executor allowed to run repository IO (virtual threads);
/// callbacks touching domain state must hop back via [CraftovScheduler#runSync].
/// `database` is the shared PostgreSQL connection pool — the game server hard-requires
/// it and refuses to start without a reachable database.
public record CraftovContext(
    EscapeFromCraftovConfig config,
    DatabaseConfig databaseConfig,
    ServiceRegistry services,
    EventBus eventBus,
    CraftovScheduler scheduler,
    ExecutorService ioExecutor,
    Database database,
    Logger logger) {

  public CraftovContext {
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
