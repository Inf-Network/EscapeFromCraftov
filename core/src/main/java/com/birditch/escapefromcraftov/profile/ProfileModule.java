package com.birditch.escapefromcraftov.profile;

import com.birditch.escapefromcraftov.api.profile.ProfileRepository;
import com.birditch.escapefromcraftov.api.profile.ProfileService;
import com.birditch.escapefromcraftov.api.profile.ProgressionService;
import com.birditch.escapefromcraftov.api.game.RaidAdmissionService;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventoryService;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.session.SessionService;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.time.SystemTimeSource;
import com.birditch.escapefromcraftov.common.time.TimeSource;
import com.birditch.escapefromcraftov.core.CraftovContext;
import com.birditch.escapefromcraftov.core.module.CraftovModule;
import com.birditch.escapefromcraftov.core.service.ServiceRegistry;
import com.birditch.escapefromcraftov.inventory.HibernatePlayerInventoryService;
/// Wires profile persistence and session tracking. PostgreSQL is the only
/// supported backend: [HibernateProfileRepository] on the shared database and
/// IO executor.
public final class ProfileModule implements CraftovModule {

  @Override
  public String id() {
    return "profile";
  }

  @Override
  public void initialize(CraftovContext context) {
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
        new HibernatePlayerInventoryService(context.database(), context.ioExecutor(),
            services.require(ItemRegistry.class)));
    services.register(RaidAdmissionService.class,
        new HibernateRaidAdmissionService(context.database(), context.ioExecutor(),
            services.require(ItemRegistry.class), timeSource));
    services.register(SessionService.class, new DefaultSessionService(timeSource));
  }

  /// Schema management belongs to the shared Hibernate bootstrap.
  @Override
  public void start() {}

  /// No resources to release: the database and IO executor are bootstrap-owned.
  @Override
  public void stop() {}
}
