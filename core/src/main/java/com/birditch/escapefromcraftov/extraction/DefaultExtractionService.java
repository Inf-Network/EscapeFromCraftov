package com.birditch.escapefromcraftov.extraction;

import com.birditch.escapefromcraftov.api.event.EventBus;
import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.extraction.PlayerExtractionReadyEvent;
import com.birditch.escapefromcraftov.api.event.extraction.PlayerExtractionLockedEvent;
import com.birditch.escapefromcraftov.api.event.extraction.PlayerExtractionAuthorizationFailedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.extraction.ExtractionCondition;
import com.birditch.escapefromcraftov.api.extraction.ExtractionContext;
import com.birditch.escapefromcraftov.api.extraction.ExtractionFailures;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPermit;
import com.birditch.escapefromcraftov.api.extraction.ExtractionService;
import com.birditch.escapefromcraftov.api.extraction.ExtractionSession;
import com.birditch.escapefromcraftov.api.extraction.ExtractionState;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.common.time.TimeSource;
import java.util.ArrayList;
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
/// [CraftovScheduler#runLater] after the point's duration; the timer fires on the
/// main thread, re-validates that the game still exists, is RUNNING and that
/// the session is still the player's active one, then moves it to SETTLING and
/// publishes [PlayerExtractionReadyEvent]. The session remains active until a
/// durable inventory transaction explicitly completes or fails it. A
/// [CraftovEndedEvent] cancels only in-world timers; settlement already in progress
/// remains retryable.
public final class DefaultExtractionService implements ExtractionService {

    private final CraftovGameService games;
    private final CraftovScheduler scheduler;
    private final EventBus eventBus;
    private final TimeSource timeSource;
    private final Logger logger;
    private final Map<UUID, ActiveExtraction> extractions = new HashMap<>();

    public DefaultExtractionService(CraftovGameService games, CraftovScheduler scheduler,
                                    EventBus eventBus, TimeSource timeSource) {
        this(games, scheduler, eventBus, timeSource,
                Logger.getLogger(DefaultExtractionService.class.getName()));
    }

    public DefaultExtractionService(CraftovGameService games, CraftovScheduler scheduler,
                                    EventBus eventBus, TimeSource timeSource, Logger logger) {
        this.games = Preconditions.checkNotNull(games, "games");
        this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
        this.eventBus = Preconditions.checkNotNull(eventBus, "eventBus");
        this.timeSource = Preconditions.checkNotNull(timeSource, "timeSource");
        this.logger = Preconditions.checkNotNull(logger, "logger");
    }

    @Override
    public Result<ExtractionSession> begin(UUID playerId, CraftovGameId gameId,
                                           ExtractionPointId pointId) {
        requireMainThread();
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(pointId, "pointId");
        Optional<CraftovGame> foundGame = games.find(gameId);
        if (foundGame.isEmpty()) {
            return Result.fail(ExtractionFailures.GAME_NOT_FOUND);
        }
        CraftovGame game = foundGame.get();
        if (game.state() != CraftovGameState.RUNNING) {
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
        List<ExtractionPermit> permits = new ArrayList<>(point.conditions().size());
        try {
            for (ExtractionCondition condition : point.conditions()) {
                Optional<ExtractionPermit> permit = condition.acquire(context);
                if (permit.isEmpty()) {
                    releasePermits(permits);
                    return Result.fail(ExtractionFailures.blocked(condition.describe()));
                }
                permits.add(permit.get());
            }
        } catch (RuntimeException failure) {
            releasePermits(permits);
            throw failure;
        }
        ExtractionSession session =
                new ExtractionSession(playerId, gameId, pointId, timeSource.now());
        ScheduledTask task;
        try {
            task = scheduler.runLater(point.duration(), () -> completeExtraction(session));
        } catch (RuntimeException failure) {
            releasePermits(permits);
            throw failure;
        }
        extractions.put(playerId, new ActiveExtraction(session, task, permits));
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
        releasePermits(active.permits());
        extractions.remove(playerId);
        return true;
    }

    @Override
    public boolean completeSettlement(UUID playerId, CraftovGameId gameId,
                                      ExtractionPointId pointId) {
        return settle(playerId, gameId, pointId, true);
    }

    @Override
    public boolean failSettlement(UUID playerId, CraftovGameId gameId,
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
    /// durable settlement coordinator even if the craftov clock expires.
    @Subscribe
    public void onCraftovEnded(CraftovEndedEvent event) {
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
            releasePermits(active.permits());
            return true;
        });
    }

    /// Runs on the main thread when the extraction timer fires. Re-validates
    /// before settling: the game must still exist and be RUNNING, and this
    /// session must still be the player's stored active one; otherwise the
    /// session fails and nothing is published (for example when the craftov ended
    /// while the timer was already dispatched). The state guard keeps a session
    /// already settled elsewhere from being failed twice.
    private void completeExtraction(ExtractionSession session) {
        ActiveExtraction active = extractions.get(session.playerId());
        boolean current = active != null && active.session() == session;
        boolean running = games.find(session.gameId())
                .filter(game -> game.state() == CraftovGameState.RUNNING)
                .isPresent();
        if (!current || !running) {
            if (session.state() == ExtractionState.EXTRACTING) {
                session.fail();
            }
            if (current) {
                releasePermits(active.permits());
                extractions.remove(session.playerId());
            }
            return;
        }
        session.beginSettlement();
        eventBus.publish(new PlayerExtractionLockedEvent(session.gameId(), session.playerId(),
                session.pointId()));
        // Lock listeners may synchronously reject an invalid/offline inventory
        // snapshot and remove this session. Never continue into an irreversible
        // paid authorization after that failure path already won.
        if (extractions.get(session.playerId()) != active
                || session.state() != ExtractionState.SETTLING) {
            return;
        }
        authorize(active, 0);
    }

    private void authorize(ActiveExtraction active, int attempt) {
        ExtractionSession session = active.session();
        authorizeAll(active.permits()).whenComplete((authorized, failure) ->
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
                        releasePermits(active.permits());
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

    private static CompletableFuture<Boolean> authorizeAll(List<ExtractionPermit> permits) {
        CompletableFuture<Boolean> result = CompletableFuture.completedFuture(true);
        for (ExtractionPermit permit : permits) {
            result = result.thenCompose(authorized -> {
                if (!authorized) return CompletableFuture.completedFuture(false);
                try {
                    return permit.authorize();
                } catch (RuntimeException synchronousFailure) {
                    return CompletableFuture.failedFuture(synchronousFailure);
                }
            });
        }
        return result;
    }

    private boolean settle(UUID playerId, CraftovGameId gameId, ExtractionPointId pointId,
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
            commitPermits(active.permits());
        } else {
            active.session().fail();
            releasePermits(active.permits());
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

    private static void commitPermits(List<ExtractionPermit> permits) {
        for (ExtractionPermit permit : permits) {
            permit.commit();
        }
    }

    private static void releasePermits(List<ExtractionPermit> permits) {
        for (int index = permits.size() - 1; index >= 0; index--) {
            permits.get(index).release();
        }
    }

    private record ActiveExtraction(ExtractionSession session, ScheduledTask task,
                                    List<ExtractionPermit> permits) {

        private ActiveExtraction {
            permits = List.copyOf(permits);
        }
    }
}
