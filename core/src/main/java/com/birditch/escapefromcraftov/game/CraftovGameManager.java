package com.birditch.escapefromcraftov.game;

import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.extraction.PlayerExtractedEvent;
import com.birditch.escapefromcraftov.api.event.game.PlayerJoinedCraftovEvent;
import com.birditch.escapefromcraftov.api.event.game.PlayerLeftCraftovEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovCreatedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovStartedEvent;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.extraction.ExtractionState;
import com.birditch.escapefromcraftov.api.extraction.ExtractionType;
import com.birditch.escapefromcraftov.api.game.GameFailures;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovResult;
import com.birditch.escapefromcraftov.api.game.CraftovTeam;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.session.PlayerSession;
import com.birditch.escapefromcraftov.api.session.ProfileLoadState;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

/// [CraftovGameService] implementation driving the full craftov lifecycle.
///
/// Main-thread only: every mutator throws [IllegalStateException] when called
/// off the main thread. Extracted players are removed from their game via the
/// [PlayerExtractedEvent] subscription (wired by [GameModule]).
public final class CraftovGameManager implements CraftovGameService {

    private static final String HEX_DIGITS = "0123456789abcdef";
    private static final ExtractionPointId DEFAULT_POINT_ID = ExtractionPointId.of("north_gate");

    private final GameManagerDeps deps;
    private final Map<CraftovGameId, CraftovGameInstance> games = new LinkedHashMap<>();

    public CraftovGameManager(GameManagerDeps deps) {
        this.deps = Preconditions.checkNotNull(deps, "deps");
    }

    @Override
    public Result<CraftovGame> create(CraftovMapId mapId) {
        return create(mapId, RaidMode.NORMAL, RaidDifficulty.NORMAL);
    }

    @Override
    public Result<CraftovGame> create(CraftovMapId mapId, RaidMode mode,
                                      RaidDifficulty difficulty) {
        requireMainThread();
        Preconditions.checkNotNull(mapId, "mapId");
        Preconditions.checkNotNull(mode, "mode");
        Preconditions.checkNotNull(difficulty, "difficulty");
        int maxInstances = deps.settings().maxInstances();
        if (games.size() >= maxInstances) {
            return Result.fail(GameFailures.instanceLimitReached(maxInstances));
        }
        CraftovGameId gameId = uniqueGameId();
        Instant now = deps.timeSource().now();
        Optional<CraftovMapRuntime> runtimeResult = deps.maps().runtime(mapId);
        if (runtimeResult.isEmpty()) {
            return Result.fail(GameFailures.MAP_NOT_AVAILABLE);
        }
        CraftovMapRuntime runtime = runtimeResult.get();
        List<ExtractionPoint> extractionPoints = runtime.extractionPoints();
        if (extractionPoints.isEmpty()) {
            extractionPoints = List.of(defaultExtractionPoint());
        }
        // Capture the complete authored snapshot. AI and POI data are part of
        // the immutable craftov contract just like spawns and extractions; using
        // the legacy five-argument constructor here silently discarded them.
        CraftovMapRuntime captured = new CraftovMapRuntime(runtime.revision(), runtime.spawns(),
                runtime.lootPoints(), runtime.lootZones(), extractionPoints,
                runtime.extractionAreas(), runtime.aiZones(), runtime.pois());
        CraftovGameInstance game = new CraftovGameInstance(
                gameId, mapId, mode, difficulty, now, captured);
        game.transitionTo(CraftovGameState.WAITING, now);
        games.put(gameId, game);
        deps.eventBus().publish(new CraftovCreatedEvent(gameId, mapId));
        return Result.ok(game);
    }

    @Override
    public Optional<CraftovGame> find(CraftovGameId id) {
        Preconditions.checkNotNull(id, "id");
        return Optional.ofNullable(games.get(id));
    }

    @Override
    public Collection<CraftovGame> all() {
        return List.copyOf(games.values());
    }

    @Override
    public Result<CraftovGame> join(CraftovGameId gameId, UUID playerId) {
        return join(gameId, playerId, playerId);
    }

    @Override
    public Result<CraftovGame> join(CraftovGameId gameId, UUID playerId, UUID teamKey) {
        requireMainThread();
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(teamKey, "teamKey");
        Optional<PlayerSession> found = deps.sessions().find(playerId);
        if (found.isEmpty()) {
            return Result.fail(GameFailures.NO_SESSION);
        }
        PlayerSession session = found.get();
        if (session.profileState() != ProfileLoadState.READY) {
            return Result.fail(GameFailures.PROFILE_NOT_READY);
        }
        if (session.currentGame().isPresent()) {
            return Result.fail(GameFailures.ALREADY_IN_GAME);
        }
        CraftovGameInstance game = games.get(gameId);
        if (game == null) {
            return Result.fail(GameFailures.GAME_NOT_FOUND);
        }
        if (game.state() != CraftovGameState.WAITING) {
            return Result.fail(GameFailures.NOT_ACCEPTING);
        }
        if (game.playerCount() >= deps.settings().maxPlayers()) {
            return Result.fail(GameFailures.GAME_FULL);
        }
        CraftovPlayer player = new CraftovPlayer(playerId, session.playerName());
        CraftovTeam team = game.teamForKeyOrCreate(teamKey, deps.settings().teamSize());
        if (!team.addMember(playerId)) {
            return Result.fail(GameFailures.GAME_FULL);
        }
        player.assignTeam(team.teamId());
        game.addPlayer(player);
        session.enterGame(gameId);
        deps.eventBus().publish(new PlayerJoinedCraftovEvent(gameId, playerId));
        return Result.ok(game);
    }

    @Override
    public Result<CraftovGame> leave(UUID playerId) {
        requireMainThread();
        Preconditions.checkNotNull(playerId, "playerId");
        Optional<PlayerSession> found = deps.sessions().find(playerId);
        Optional<CraftovGameInstance> current = found.flatMap(PlayerSession::currentGame)
                .map(games::get);
        if (found.isEmpty() || current.isEmpty()) {
            return Result.fail(GameFailures.NOT_IN_GAME);
        }
        CraftovGameInstance game = current.get();
        game.removePlayer(playerId);
        found.get().leaveGame();
        deps.eventBus().publish(new PlayerLeftCraftovEvent(game.id(), playerId));
        scheduleEmptyRunningGameStop(game);
        return Result.ok(game);
    }

    @Override
    public Result<CraftovGame> start(CraftovGameId id) {
        requireMainThread();
        Preconditions.checkNotNull(id, "id");
        CraftovGameInstance game = games.get(id);
        if (game == null) {
            return Result.fail(GameFailures.GAME_NOT_FOUND);
        }
        if (game.state() != CraftovGameState.WAITING) {
            return Result.fail(GameFailures.cannotStartFrom(game.state()));
        }
        Instant now = deps.timeSource().now();
        game.transitionTo(CraftovGameState.STARTING, now);
        game.transitionTo(CraftovGameState.RUNNING, now);
        deps.eventBus().publish(new CraftovStartedEvent(id));
        Duration duration = Duration.ofSeconds(deps.settings().durationSeconds());
        game.setEndTask(deps.scheduler().runLater(duration, () -> autoStop(id)));
        return Result.ok(game);
    }

    @Override
    public Result<CraftovGame> stop(CraftovGameId id) {
        requireMainThread();
        Preconditions.checkNotNull(id, "id");
        CraftovGameInstance game = games.get(id);
        if (game == null) {
            return Result.fail(GameFailures.GAME_NOT_FOUND);
        }
        Instant now = deps.timeSource().now();
        game.transitionTo(CraftovGameState.ENDING, now);
        CraftovResult result = buildResult(game, now);
        game.setResult(result);
        game.transitionTo(CraftovGameState.FINISHED, now);
        deps.eventBus().publish(new CraftovEndedEvent(id, result));
        destroy(game, now);
        return Result.ok(game);
    }

    @Override
    public void destroyAll() {
        requireMainThread();
        Instant now = deps.timeSource().now();
        for (CraftovGameInstance game : List.copyOf(games.values())) {
            try {
                destroy(game, now);
            } catch (RuntimeException e) {
                deps.logger().log(Level.SEVERE,
                        "Failed to destroy game " + game.id().value(), e);
                games.remove(game.id());
            }
        }
    }

    @Override
    public int activeCount() {
        return games.size();
    }

    /// Removes the extracted player from their game while keeping the
    /// extraction record for the final [CraftovResult]. Unknown games are ignored
    /// (the game may already have been destroyed).
    @Subscribe
    public void onPlayerExtracted(PlayerExtractedEvent event) {
        CraftovGameInstance game = games.get(event.gameId());
        if (game == null) {
            return;
        }
        game.markExtracted(event.playerId());
        game.removePlayer(event.playerId());
        deps.sessions().find(event.playerId()).ifPresent(PlayerSession::leaveGame);
        scheduleEmptyRunningGameStop(game);
    }

    /// Ends a vacated live room on the next scheduler turn. Deferring one turn
    /// lets the current leave/extraction event finish dispatching before
    /// [CraftovEndedEvent] tears down its Bukkit world. Every caller reaches
    /// this point only after its durable inventory settlement has completed.
    private void scheduleEmptyRunningGameStop(CraftovGameInstance game) {
        if (game.state() != CraftovGameState.RUNNING || game.playerCount() != 0) {
            return;
        }
        CraftovGameId id = game.id();
        deps.scheduler().runLater(Duration.ZERO, () -> {
            CraftovGameInstance current = games.get(id);
            if (current == null || current.state() != CraftovGameState.RUNNING
                    || current.playerCount() != 0) {
                return;
            }
            stop(id).error().ifPresent(message -> deps.logger()
                    .warning("Empty-game stop for " + id.value() + " skipped: " + message));
        });
    }

    private void autoStop(CraftovGameId id) {
        stop(id).error().ifPresent(message -> deps.logger()
                .warning("Auto-stop for game " + id.value() + " skipped: " + message));
    }

    private CraftovResult buildResult(CraftovGameInstance game, Instant endedAt) {
        Set<UUID> extracted = game.extractedPlayerIds();
        Set<UUID> remaining = game.players().stream()
                .map(CraftovPlayer::playerId)
                .filter(playerId -> !extracted.contains(playerId))
                .collect(Collectors.toUnmodifiableSet());
        return new CraftovResult(game.id(), endedAt, extracted, remaining);
    }

    private void destroy(CraftovGameInstance game, Instant now) {
        game.cancelEndTask();
        for (CraftovPlayer player : game.players()) {
            deps.sessions().find(player.playerId()).ifPresent(PlayerSession::leaveGame);
        }
        while (game.state() != CraftovGameState.DESTROYED) {
            game.transitionTo(nextTowardDestroyed(game.state()), now);
        }
        games.remove(game.id());
    }

    /// Next legal step on the shortest path to DESTROYED; active games pass
    /// through ENDING and FINISHED because RUNNING cannot be destroyed directly.
    private static CraftovGameState nextTowardDestroyed(CraftovGameState state) {
        return switch (state) {
            case CREATED, WAITING, PREPARING, FINISHED, DESTROYED -> CraftovGameState.DESTROYED;
            case STARTING, RUNNING -> CraftovGameState.ENDING;
            case ENDING -> CraftovGameState.FINISHED;
        };
    }

    private ExtractionPoint defaultExtractionPoint() {
        return new ExtractionPoint(DEFAULT_POINT_ID, "北门", ExtractionType.NORMAL,
                Duration.ofSeconds(deps.extraction().defaultDurationSeconds()), List.of(),
                ExtractionState.AVAILABLE);
    }

    private CraftovGameId uniqueGameId() {
        while (true) {
            StringBuilder value = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                value.append(HEX_DIGITS.charAt(deps.random().nextInt(HEX_DIGITS.length())));
            }
            CraftovGameId id = CraftovGameId.of(value.toString());
            if (!games.containsKey(id)) {
                return id;
            }
        }
    }

    private void requireMainThread() {
        if (!deps.scheduler().isMainThread()) {
            throw new IllegalStateException("CraftovGameService must be called from the main thread");
        }
    }
}
