package com.birditch.raidcore.extraction;

import com.birditch.raidcore.api.event.EventBus;
import com.birditch.raidcore.api.event.extraction.PlayerExtractedEvent;
import com.birditch.raidcore.api.extraction.ExtractionCondition;
import com.birditch.raidcore.api.extraction.ExtractionContext;
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

/// Default [ExtractionService]. Main-thread confined: every public method
/// throws [IllegalStateException] when called off the main thread.
///
/// A successful [#begin] schedules completion via
/// [RaidScheduler#runLater] after the point's duration; the timer fires on the
/// main thread, settles the session as completed, removes it from the active
/// map and only then publishes [PlayerExtractedEvent], so listeners observing
/// the event never see the session as still active.
public final class DefaultExtractionService implements ExtractionService {

    private final RaidGameService games;
    private final RaidScheduler scheduler;
    private final EventBus eventBus;
    private final TimeSource timeSource;
    private final Map<UUID, ActiveExtraction> extractions = new HashMap<>();

    public DefaultExtractionService(RaidGameService games, RaidScheduler scheduler,
                                    EventBus eventBus, TimeSource timeSource) {
        this.games = Preconditions.checkNotNull(games, "games");
        this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
        this.eventBus = Preconditions.checkNotNull(eventBus, "eventBus");
        this.timeSource = Preconditions.checkNotNull(timeSource, "timeSource");
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
            return Result.fail("Game not found");
        }
        RaidGame game = foundGame.get();
        if (game.state() != RaidGameState.RUNNING) {
            return Result.fail("Game is not running");
        }
        boolean inGame = game.players().stream()
                .anyMatch(player -> player.playerId().equals(playerId));
        if (!inGame) {
            return Result.fail("Player is not in this game");
        }
        Optional<ExtractionPoint> foundPoint = game.extractionPoints().stream()
                .filter(point -> point.id().equals(pointId))
                .findFirst();
        if (foundPoint.isEmpty()) {
            return Result.fail("Extraction point not found");
        }
        ExtractionPoint point = foundPoint.get();
        if (point.state() == ExtractionState.LOCKED) {
            return Result.fail("Extraction point is locked");
        }
        if (extractions.containsKey(playerId)) {
            return Result.fail("Extraction already in progress");
        }
        ExtractionContext context = new ExtractionContext(playerId, gameId);
        for (ExtractionCondition condition : point.conditions()) {
            if (!condition.isSatisfied(context)) {
                return Result.fail("Extraction blocked: " + condition.describe());
            }
        }
        ExtractionSession session =
                new ExtractionSession(playerId, gameId, pointId, timeSource.now());
        ScheduledTask task =
                scheduler.runLater(point.duration(), () -> completeExtraction(session));
        extractions.put(playerId, new ActiveExtraction(session, task));
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
        active.task().cancel();
        active.session().fail();
        extractions.remove(playerId);
        return true;
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

    /// Runs on the main thread when the extraction timer fires; a cancelled
    /// timer never fires, so the session here is always still EXTRACTING.
    private void completeExtraction(ExtractionSession session) {
        session.complete();
        extractions.remove(session.playerId());
        eventBus.publish(new PlayerExtractedEvent(session.gameId(), session.playerId(),
                session.pointId()));
    }

    private void requireMainThread() {
        if (!scheduler.isMainThread()) {
            throw new IllegalStateException(
                    "ExtractionService must be called from the main thread");
        }
    }

    private record ActiveExtraction(ExtractionSession session, ScheduledTask task) {
    }
}
