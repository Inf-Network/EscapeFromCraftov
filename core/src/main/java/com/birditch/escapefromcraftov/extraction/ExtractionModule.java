package com.birditch.escapefromcraftov.extraction;

import com.birditch.escapefromcraftov.api.event.EventSubscription;
import com.birditch.escapefromcraftov.api.extraction.ExtractionService;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.time.SystemTimeSource;
import com.birditch.escapefromcraftov.core.CraftovContext;
import com.birditch.escapefromcraftov.core.module.CraftovModule;
import org.jspecify.annotations.Nullable;

/// Wires the extraction domain: registers [ExtractionService] and subscribes it
/// to game events so a craftov that ends cancels its pending extractions.
///
/// Requires [CraftovGameService], so the game module must be initialized first
/// (bootstrap order: item, loot, profile, economy, combat, game, extraction).
public final class ExtractionModule implements CraftovModule {

    private @Nullable EventSubscription subscription;

    @Override
    public String id() {
        return "extraction";
    }

    @Override
    public void initialize(CraftovContext context) {
        Preconditions.checkNotNull(context, "context");
        CraftovGameService games = context.services().require(CraftovGameService.class);
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
