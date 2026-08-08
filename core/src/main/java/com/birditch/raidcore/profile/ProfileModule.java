package com.birditch.raidcore.profile;

import com.birditch.raidcore.api.profile.ProfileException;
import com.birditch.raidcore.api.profile.ProfileRepository;
import com.birditch.raidcore.api.profile.ProfileService;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.time.SystemTimeSource;
import com.birditch.raidcore.common.time.TimeSource;
import com.birditch.raidcore.core.RaidContext;
import com.birditch.raidcore.core.module.RaidModule;
import com.birditch.raidcore.core.service.ServiceRegistry;
import org.jspecify.annotations.Nullable;

/// Wires profile persistence and session tracking.
///
/// The repository backend follows the configured database type: `MEMORY` uses
/// [InMemoryProfileRepository], `POSTGRESQL` uses [PostgresProfileRepository]
/// on the shared database and IO executor.
public final class ProfileModule implements RaidModule {

  private @Nullable PostgresProfileRepository postgresRepository;

  @Override
  public String id() {
    return "profile";
  }

  @Override
  public void initialize(RaidContext context) {
    Preconditions.checkNotNull(context, "context");
    ProfileRepository repository = createRepository(context);
    TimeSource timeSource = new SystemTimeSource();
    ServiceRegistry services = context.services();
    services.register(ProfileRepository.class, repository);
    services.register(
        ProfileService.class, new DefaultProfileService(repository, timeSource, context.logger()));
    services.register(SessionService.class, new DefaultSessionService(timeSource));
  }

  /// PostgreSQL schema creation happens here: a one-time blocking call is
  /// acceptable at boot, before any player traffic.
  @Override
  public void start() {
    if (postgresRepository != null) {
      postgresRepository.initSchema();
    }
  }

  /// No resources to release: the database and IO executor are owned by the bootstrap.
  @Override
  public void stop() {
  }

  private ProfileRepository createRepository(RaidContext context) {
    return switch (context.databaseConfig().type()) {
      case MEMORY -> new InMemoryProfileRepository();
      case POSTGRESQL -> {
        PostgresProfileRepository repository =
            new PostgresProfileRepository(
                context
                    .database()
                    .orElseThrow(
                        () ->
                            new ProfileException(
                                "Database connection required for POSTGRESQL profile repository")),
                context.ioExecutor());
        postgresRepository = repository;
        yield repository;
      }
    };
  }
}
