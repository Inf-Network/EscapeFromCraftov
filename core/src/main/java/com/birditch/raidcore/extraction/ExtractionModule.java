package com.birditch.raidcore.extraction;

import com.birditch.raidcore.api.extraction.ExtractionService;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.time.SystemTimeSource;
import com.birditch.raidcore.core.RaidContext;
import com.birditch.raidcore.core.module.RaidModule;

/// Wires the extraction domain: registers [ExtractionService].
///
/// Requires [RaidGameService], so the game module must be initialized first
/// (bootstrap order: item, loot, profile, economy, combat, game, extraction).
public final class ExtractionModule implements RaidModule {

    @Override
    public String id() {
        return "extraction";
    }

    @Override
    public void initialize(RaidContext context) {
        Preconditions.checkNotNull(context, "context");
        RaidGameService games = context.services().require(RaidGameService.class);
        context.services().register(ExtractionService.class, new DefaultExtractionService(
                games, context.scheduler(), context.eventBus(), new SystemTimeSource()));
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }
}
