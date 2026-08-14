package com.birditch.escapefromcraftov.economy;

import com.birditch.escapefromcraftov.api.economy.EconomyService;
import com.birditch.escapefromcraftov.api.profile.ProfileService;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.time.SystemTimeSource;
import com.birditch.escapefromcraftov.core.CraftovContext;
import com.birditch.escapefromcraftov.core.module.CraftovModule;

/// Wires the economy domain. Requires the profile module to be initialized
/// first (bootstrap order: item, loot, profile, economy, ...).
public final class EconomyModule implements CraftovModule {

    @Override
    public String id() {
        return "economy";
    }

    @Override
    public void initialize(CraftovContext context) {
        Preconditions.checkNotNull(context, "context");
        ProfileService profiles = context.services().require(ProfileService.class);
        EconomyService economy =
                new DefaultEconomyService(profiles, context.scheduler(), new SystemTimeSource(),
                        new HibernateEconomyRepository(context.database(), context.ioExecutor()));
        context.services().register(EconomyService.class, economy);
    }

    @Override
    public void start() {
        // Economy has no start-time behavior; the service is ready after initialize.
    }

    @Override
    public void stop() {
        // Economy holds no resources to release.
    }
}
