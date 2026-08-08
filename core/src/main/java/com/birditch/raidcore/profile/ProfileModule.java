package com.birditch.raidcore.profile;

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

/// Wires profile persistence and session tracking. PostgreSQL is the only
/// supported backend: [PostgresProfileRepository] on the shared database and
/// IO executor.
public final class ProfileModule implements RaidModule {

  private @Nullable PostgresProfileRepository repository;

  @Override
  public String id() {
    return "profile";
  }

  @Override
  public void initialize(RaidContext context) {
    Preconditions.checkNotNull(context, "context");
    PostgresProfileRepository postgres =
        new PostgresProfileRepository(context.database(), context.ioExecutor());
    repository = postgres;
    TimeSource timeSource = new SystemTimeSource();
    ServiceRegistry services = context.services();
    services.register(ProfileRepository.class, postgres);
    services.register(
        ProfileService.class, new DefaultProfileService(postgres, timeSource, context.logger()));
    services.register(SessionService.class, new DefaultSessionService(timeSource));
  }

  /// PostgreSQL schema creation happens here: a one-time blocking call is
  /// acceptable at boot, before any player traffic.
  @Override
  public void start() {
    PostgresProfileRepository postgres = repository;
    if (postgres != null) {
      postgres.initSchema();
    }
  }

  /// No resources to release: the database and IO executor are owned by the bootstrap.
  @Override
  public void stop() {
  }
}
