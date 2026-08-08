package com.birditch.raidcore.economy;

import com.birditch.raidcore.api.economy.EconomyService;
import com.birditch.raidcore.api.profile.ProfileService;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.time.SystemTimeSource;
import com.birditch.raidcore.core.RaidContext;
import com.birditch.raidcore.core.module.RaidModule;

/// Wires the economy domain. Requires the profile module to be initialized
/// first (bootstrap order: item, loot, profile, economy, ...).
public final class EconomyModule implements RaidModule {

    @Override
    public String id() {
        return "economy";
    }

    @Override
    public void initialize(RaidContext context) {
        Preconditions.checkNotNull(context, "context");
        ProfileService profiles = context.services().require(ProfileService.class);
        EconomyService economy =
                new DefaultEconomyService(profiles, context.scheduler(), new SystemTimeSource());
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
