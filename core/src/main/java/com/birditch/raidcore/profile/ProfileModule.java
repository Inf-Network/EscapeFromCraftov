package com.birditch.raidcore.profile;

import com.birditch.raidcore.api.profile.ProfileRepository;
import com.birditch.raidcore.api.profile.ProfileService;
import com.birditch.raidcore.api.profile.ProgressionService;
import com.birditch.raidcore.api.inventory.PlayerInventoryService;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.time.SystemTimeSource;
import com.birditch.raidcore.common.time.TimeSource;
import com.birditch.raidcore.core.RaidContext;
import com.birditch.raidcore.core.module.RaidModule;
import com.birditch.raidcore.core.service.ServiceRegistry;
import com.birditch.raidcore.inventory.HibernatePlayerInventoryService;
/// Wires profile persistence and session tracking. PostgreSQL is the only
/// supported backend: [HibernateProfileRepository] on the shared database and
/// IO executor.
public final class ProfileModule implements RaidModule {

  @Override
  public String id() {
    return "profile";
  }

  @Override
  public void initialize(RaidContext context) {
    Preconditions.checkNotNull(context, "context");
    HibernateProfileRepository repository =
        new HibernateProfileRepository(context.database(), context.ioExecutor());
    TimeSource timeSource = new SystemTimeSource();
    ServiceRegistry services = context.services();
    services.register(ProfileRepository.class, repository);
    ProfileService profiles = new DefaultProfileService(repository, timeSource, context.logger());
    services.register(ProfileService.class, profiles);
    services.register(ProgressionService.class, new DefaultProgressionService(profiles,
        new HibernateProgressionRepository(context.database(), context.ioExecutor()),
        context.scheduler(), timeSource));
    services.register(PlayerInventoryService.class,
        new HibernatePlayerInventoryService(context.database(), context.ioExecutor()));
    services.register(SessionService.class, new DefaultSessionService(timeSource));
  }

  /// Schema management belongs to the shared Hibernate bootstrap.
  @Override
  public void start() {}

  /// No resources to release: the database and IO executor are bootstrap-owned.
  @Override
  public void stop() {}
}
