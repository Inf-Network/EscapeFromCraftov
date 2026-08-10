package com.birditch.raidcore.extraction;

import com.birditch.raidcore.api.event.EventSubscription;
import com.birditch.raidcore.api.extraction.ExtractionService;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.time.SystemTimeSource;
import com.birditch.raidcore.core.RaidContext;
import com.birditch.raidcore.core.module.RaidModule;
import org.jspecify.annotations.Nullable;

/// Wires the extraction domain: registers [ExtractionService] and subscribes it
/// to game events so a raid that ends cancels its pending extractions.
///
/// Requires [RaidGameService], so the game module must be initialized first
/// (bootstrap order: item, loot, profile, economy, combat, game, extraction).
public final class ExtractionModule implements RaidModule {

    private @Nullable EventSubscription subscription;

    @Override
    public String id() {
        return "extraction";
    }

    @Override
    public void initialize(RaidContext context) {
        Preconditions.checkNotNull(context, "context");
        RaidGameService games = context.services().require(RaidGameService.class);
        DefaultExtractionService service = new DefaultExtractionService(
                games, context.scheduler(), context.eventBus(), new SystemTimeSource(),
                context.logger());
        context.services().register(ExtractionService.class, service);
        subscription = context.eventBus().register(service);
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        if (subscription != null) {
            subscription.unsubscribe();
            subscription = null;
        }
    }
}
