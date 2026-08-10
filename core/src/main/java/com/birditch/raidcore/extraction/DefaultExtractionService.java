package com.birditch.raidcore.extraction;

import com.birditch.raidcore.api.event.EventBus;
import com.birditch.raidcore.api.event.Subscribe;
import com.birditch.raidcore.api.event.extraction.PlayerExtractionReadyEvent;
import com.birditch.raidcore.api.event.extraction.PlayerExtractionLockedEvent;
import com.birditch.raidcore.api.event.extraction.PlayerExtractionAuthorizationFailedEvent;
import com.birditch.raidcore.api.event.game.RaidEndedEvent;
import com.birditch.raidcore.api.extraction.ExtractionCondition;
import com.birditch.raidcore.api.extraction.ExtractionContext;
import com.birditch.raidcore.api.extraction.ExtractionFailures;
import com.birditch.raidcore.api.extraction.ExtractionPoint;
import com.birditch.raidcore.api.extraction.ExtractionPointId;
import com.birditch.raidcore.api.extraction.ExtractionService;
import com.birditch.raidcore.api.extraction.ExtractionSession;
import com.birditch.raidcore.api.extraction.ExtractionState;
import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.api.scheduler.ScheduledTask;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.result.Result;
import com.birditch.raidcore.common.time.TimeSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Default [ExtractionService]. Main-thread confined: every public method
/// throws [IllegalStateException] when called off the main thread.
///
/// A successful [#begin] schedules completion via
/// [RaidScheduler#runLater] after the point's duration; the timer fires on the
/// main thread, re-validates that the game still exists, is RUNNING and that
/// the session is still the player's active one, then moves it to SETTLING and
/// publishes [PlayerExtractionReadyEvent]. The session remains active until a
/// durable inventory transaction explicitly completes or fails it. A
/// [RaidEndedEvent] cancels only in-world timers; settlement already in progress
/// remains retryable.
public final class DefaultExtractionService implements ExtractionService {

    private final RaidGameService games;
    private final RaidScheduler scheduler;
    private final EventBus eventBus;
    private final TimeSource timeSource;
    private final Logger logger;
    private final Map<UUID, ActiveExtraction> extractions = new HashMap<>();

    public DefaultExtractionService(RaidGameService games, RaidScheduler scheduler,
                                    EventBus eventBus, TimeSource timeSource) {
        this(games, scheduler, eventBus, timeSource,
                Logger.getLogger(DefaultExtractionService.class.getName()));
    }

    public DefaultExtractionService(RaidGameService games, RaidScheduler scheduler,
                                    EventBus eventBus, TimeSource timeSource, Logger logger) {
        this.games = Preconditions.checkNotNull(games, "games");
        this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
        this.eventBus = Preconditions.checkNotNull(eventBus, "eventBus");
        this.timeSource = Preconditions.checkNotNull(timeSource, "timeSource");
        this.logger = Preconditions.checkNotNull(logger, "logger");
    }

    @Override
    public Result<ExtractionSession> begin(UUID playerId, RaidGameId gameId,
                                           ExtractionPointId pointId) {
        requireMainThread();
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(pointId, "pointId");
        Optional<RaidGame> foundGame = games.find(gameId);
        if (foundGame.isEmpty()) {
            return Result.fail(ExtractionFailures.GAME_NOT_FOUND);
        }
        RaidGame game = foundGame.get();
        if (game.state() != RaidGameState.RUNNING) {
            return Result.fail(ExtractionFailures.GAME_NOT_RUNNING);
        }
        boolean inGame = game.players().stream()
                .anyMatch(player -> player.playerId().equals(playerId));
        if (!inGame) {
            return Result.fail(ExtractionFailures.PLAYER_NOT_IN_GAME);
        }
        Optional<ExtractionPoint> foundPoint = game.extractionPoints().stream()
                .filter(point -> point.id().equals(pointId))
                .findFirst();
        if (foundPoint.isEmpty()) {
            return Result.fail(ExtractionFailures.POINT_NOT_FOUND);
        }
        ExtractionPoint point = foundPoint.get();
        if (point.state() == ExtractionState.LOCKED) {
            return Result.fail(ExtractionFailures.POINT_LOCKED);
        }
        if (extractions.containsKey(playerId)) {
            return Result.fail(ExtractionFailures.ALREADY_EXTRACTING);
        }
        ExtractionContext context = new ExtractionContext(playerId, gameId);
        for (ExtractionCondition condition : point.conditions()) {
            if (!condition.isSatisfied(context)) {
                return Result.fail(ExtractionFailures.blocked(condition.describe()));
            }
        }
        ExtractionSession session =
                new ExtractionSession(playerId, gameId, pointId, timeSource.now());
        ScheduledTask task =
                scheduler.runLater(point.duration(), () -> completeExtraction(session));
        extractions.put(playerId, new ActiveExtraction(session, task, point.conditions()));
        return Result.ok(session);
    }

    @Override
    public boolean cancel(UUID playerId) {
        requireMainThread();
        Preconditions.checkNotNull(playerId, "playerId");
        ActiveExtraction active = extractions.get(playerId);
        if (active == null) {
            return false;
        }
        if (active.session().state() != ExtractionState.EXTRACTING) {
            return false;
        }
        active.task().cancel();
        active.session().fail();
        extractions.remove(playerId);
        return true;
    }

    @Override
    public boolean completeSettlement(UUID playerId, RaidGameId gameId,
                                      ExtractionPointId pointId) {
        return settle(playerId, gameId, pointId, true);
    }

    @Override
    public boolean failSettlement(UUID playerId, RaidGameId gameId,
                                  ExtractionPointId pointId) {
        return settle(playerId, gameId, pointId, false);
    }

    @Override
    public Optional<ExtractionSession> activeSession(UUID playerId) {
        requireMainThread();
        Preconditions.checkNotNull(playerId, "playerId");
        return Optional.ofNullable(extractions.get(playerId)).map(ActiveExtraction::session);
    }

    @Override
    public int activeCount() {
        requireMainThread();
        return extractions.size();
    }

    /// Cancels only extraction timers that have not completed. A SETTLING
    /// session has already earned its extraction and remains available to the
    /// durable settlement coordinator even if the raid clock expires.
    @Subscribe
    public void onRaidEnded(RaidEndedEvent event) {
        requireMainThread();
        Preconditions.checkNotNull(event, "event");
        extractions.values().removeIf(active -> {
            if (!active.session().gameId().equals(event.gameId())) {
                return false;
            }
            if (active.session().state() == ExtractionState.SETTLING) {
                return false;
            }
            active.task().cancel();
            active.session().fail();
            return true;
        });
    }

    /// Runs on the main thread when the extraction timer fires. Re-validates
    /// before settling: the game must still exist and be RUNNING, and this
    /// session must still be the player's stored active one; otherwise the
    /// session fails and nothing is published (for example when the raid ended
    /// while the timer was already dispatched). The state guard keeps a session
    /// already settled elsewhere from being failed twice.
    private void completeExtraction(ExtractionSession session) {
        ActiveExtraction active = extractions.get(session.playerId());
        boolean current = active != null && active.session() == session;
        boolean running = games.find(session.gameId())
                .filter(game -> game.state() == RaidGameState.RUNNING)
                .isPresent();
        if (!current || !running) {
            if (session.state() == ExtractionState.EXTRACTING) {
                session.fail();
            }
            if (current) {
                extractions.remove(session.playerId());
            }
            return;
        }
        session.beginSettlement();
        eventBus.publish(new PlayerExtractionLockedEvent(session.gameId(), session.playerId(),
                session.pointId()));
        authorize(active, 0);
    }

    private void authorize(ActiveExtraction active, int attempt) {
        ExtractionSession session = active.session();
        ExtractionContext context = new ExtractionContext(session.playerId(), session.gameId());
        authorizeAll(active.conditions(), context).whenComplete((authorized, failure) ->
                scheduler.runSync(() -> {
                    ActiveExtraction current = extractions.get(session.playerId());
                    if (current != active || session.state() != ExtractionState.SETTLING) {
                        return;
                    }
                    if (failure != null) {
                        logger.log(Level.WARNING,
                                "撤离持久条件校验失败，将在 5 秒后重试 (第 " + (attempt + 1)
                                        + " 次):" + session.playerId(), failure);
                        scheduler.runLater(java.time.Duration.ofSeconds(5),
                                () -> authorize(active, attempt + 1));
                        return;
                    }
                    if (!Boolean.TRUE.equals(authorized)) {
                        session.fail();
                        extractions.remove(session.playerId());
                        eventBus.publish(new PlayerExtractionAuthorizationFailedEvent(
                                session.gameId(), session.playerId(), session.pointId(),
                                "撤离条件未通过"));
                        return;
                    }
                    eventBus.publish(new PlayerExtractionReadyEvent(
                            session.gameId(), session.playerId(), session.pointId()));
                }));
    }

    private static CompletableFuture<Boolean> authorizeAll(
            List<ExtractionCondition> conditions, ExtractionContext context) {
        CompletableFuture<Boolean> result = CompletableFuture.completedFuture(true);
        for (ExtractionCondition condition : conditions) {
            result = result.thenCompose(authorized -> authorized
                    ? condition.authorize(context)
                    : CompletableFuture.completedFuture(false));
        }
        return result;
    }

    private boolean settle(UUID playerId, RaidGameId gameId, ExtractionPointId pointId,
                           boolean successful) {
        requireMainThread();
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(pointId, "pointId");
        ActiveExtraction active = extractions.get(playerId);
        if (active == null || active.session().state() != ExtractionState.SETTLING
                || !active.session().gameId().equals(gameId)
                || !active.session().pointId().equals(pointId)) {
            return false;
        }
        if (successful) {
            active.session().complete();
        } else {
            active.session().fail();
        }
        extractions.remove(playerId);
        return true;
    }

    private void requireMainThread() {
        if (!scheduler.isMainThread()) {
            throw new IllegalStateException(
                    "ExtractionService must be called from the main thread");
        }
    }

    private record ActiveExtraction(ExtractionSession session, ScheduledTask task,
                                    List<ExtractionCondition> conditions) {

        private ActiveExtraction {
            conditions = List.copyOf(conditions);
        }
    }
}
