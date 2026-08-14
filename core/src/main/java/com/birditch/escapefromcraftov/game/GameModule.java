package com.birditch.escapefromcraftov.game;

import com.birditch.escapefromcraftov.api.event.EventSubscription;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.session.SessionService;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.random.ThreadLocalRandomProvider;
import com.birditch.escapefromcraftov.common.time.SystemTimeSource;
import com.birditch.escapefromcraftov.core.CraftovContext;
import com.birditch.escapefromcraftov.core.module.CraftovModule;
import org.jspecify.annotations.Nullable;

/// Wires the game domain: registers [CraftovGameService] and subscribes the
/// manager's `@Subscribe` handler for
/// [com.birditch.escapefromcraftov.api.event.extraction.PlayerExtractedEvent] on the
/// event bus; the subscription is released again in [#stop].
///
/// Requires [SessionService], so the profile module must be initialized first
/// (bootstrap order: item, loot, profile, economy, combat, game, extraction).
public final class GameModule implements CraftovModule {

    private final CraftovMapProvider maps;
    private @Nullable EventSubscription subscription;

    public GameModule() {
        this(CraftovMapProvider.EMPTY);
    }

    public GameModule(CraftovMapProvider maps) {
        this.maps = Preconditions.checkNotNull(maps, "maps");
    }

    @Override
    public String id() {
        return "game";
    }

    @Override
    public void initialize(CraftovContext context) {
        Preconditions.checkNotNull(context, "context");
        SessionService sessions = context.services().require(SessionService.class);
        CraftovGameManager manager = new CraftovGameManager(new GameManagerDeps(
                sessions, context.eventBus(), context.scheduler(),
                new ThreadLocalRandomProvider(), new SystemTimeSource(),
                context.config().game(), context.config().extraction(), maps, context.logger()));
        context.services().register(CraftovGameService.class, manager);
        subscription = context.eventBus().register(manager);
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
