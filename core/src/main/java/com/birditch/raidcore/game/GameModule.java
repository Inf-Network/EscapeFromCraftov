package com.birditch.raidcore.game;

import com.birditch.raidcore.api.event.EventSubscription;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.random.ThreadLocalRandomProvider;
import com.birditch.raidcore.common.time.SystemTimeSource;
import com.birditch.raidcore.core.RaidContext;
import com.birditch.raidcore.core.module.RaidModule;
import org.jspecify.annotations.Nullable;

/// Wires the game domain: registers [RaidGameService] and subscribes the
/// manager's `@Subscribe` handler for
/// [com.birditch.raidcore.api.event.extraction.PlayerExtractedEvent] on the
/// event bus; the subscription is released again in [#stop].
///
/// Requires [SessionService], so the profile module must be initialized first
/// (bootstrap order: item, loot, profile, economy, combat, game, extraction).
public final class GameModule implements RaidModule {

    private final RaidMapProvider maps;
    private @Nullable EventSubscription subscription;

    public GameModule() {
        this(RaidMapProvider.EMPTY);
    }

    public GameModule(RaidMapProvider maps) {
        this.maps = Preconditions.checkNotNull(maps, "maps");
    }

    @Override
    public String id() {
        return "game";
    }

    @Override
    public void initialize(RaidContext context) {
        Preconditions.checkNotNull(context, "context");
        SessionService sessions = context.services().require(SessionService.class);
        RaidGameManager manager = new RaidGameManager(new GameManagerDeps(
                sessions, context.eventBus(), context.scheduler(),
                new ThreadLocalRandomProvider(), new SystemTimeSource(),
                context.config().game(), context.config().extraction(), maps, context.logger()));
        context.services().register(RaidGameService.class, manager);
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
