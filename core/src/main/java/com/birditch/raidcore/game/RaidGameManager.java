package com.birditch.raidcore.game;

import com.birditch.raidcore.api.event.Subscribe;
import com.birditch.raidcore.api.event.extraction.PlayerExtractedEvent;
import com.birditch.raidcore.api.event.game.PlayerJoinedRaidEvent;
import com.birditch.raidcore.api.event.game.PlayerLeftRaidEvent;
import com.birditch.raidcore.api.event.game.RaidCreatedEvent;
import com.birditch.raidcore.api.event.game.RaidEndedEvent;
import com.birditch.raidcore.api.event.game.RaidStartedEvent;
import com.birditch.raidcore.api.extraction.ExtractionPoint;
import com.birditch.raidcore.api.extraction.ExtractionPointId;
import com.birditch.raidcore.api.extraction.ExtractionState;
import com.birditch.raidcore.api.extraction.ExtractionType;
import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.game.RaidPlayer;
import com.birditch.raidcore.api.game.RaidResult;
import com.birditch.raidcore.api.game.RaidTeam;
import com.birditch.raidcore.api.session.PlayerSession;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.result.Result;
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

/// [RaidGameService] implementation driving the full raid lifecycle.
///
/// Main-thread only: every mutator throws [IllegalStateException] when called
/// off the main thread. Extracted players are removed from their game via the
/// [PlayerExtractedEvent] subscription (wired by [GameModule]).
public final class RaidGameManager implements RaidGameService {

    private static final String HEX_DIGITS = "0123456789abcdef";
    private static final ExtractionPointId DEFAULT_POINT_ID = ExtractionPointId.of("north_gate");

    private final GameManagerDeps deps;
    private final Map<RaidGameId, RaidGameInstance> games = new LinkedHashMap<>();

    public RaidGameManager(GameManagerDeps deps) {
        this.deps = Preconditions.checkNotNull(deps, "deps");
    }

    @Override
    public RaidGame create(RaidMapId mapId) {
        requireMainThread();
        Preconditions.checkNotNull(mapId, "mapId");
        RaidGameId gameId = uniqueGameId();
        Instant now = deps.timeSource().now();
        RaidGameInstance game =
                new RaidGameInstance(gameId, mapId, now, List.of(defaultExtractionPoint()));
        game.transitionTo(RaidGameState.WAITING, now);
        games.put(gameId, game);
        deps.eventBus().publish(new RaidCreatedEvent(gameId, mapId));
        return game;
    }

    @Override
    public Optional<RaidGame> find(RaidGameId id) {
        Preconditions.checkNotNull(id, "id");
        return Optional.ofNullable(games.get(id));
    }

    @Override
    public Collection<RaidGame> all() {
        return List.copyOf(games.values());
    }

    @Override
    public Result<RaidGame> join(RaidGameId gameId, UUID playerId) {
        requireMainThread();
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(playerId, "playerId");
        Optional<PlayerSession> found = deps.sessions().find(playerId);
        if (found.isEmpty()) {
            return Result.fail("Player has no session");
        }
        PlayerSession session = found.get();
        if (session.currentGame().isPresent()) {
            return Result.fail("Player is already in a game");
        }
        RaidGameInstance game = games.get(gameId);
        if (game == null) {
            return Result.fail("Game not found");
        }
        if (game.state() != RaidGameState.WAITING) {
            return Result.fail("Game is not accepting players");
        }
        if (game.playerCount() >= deps.settings().maxPlayers()) {
            return Result.fail("Game is full");
        }
        RaidPlayer player = new RaidPlayer(playerId, session.playerName());
        RaidTeam team = game.teamWithSpaceOrCreate(deps.settings().teamSize());
        team.addMember(playerId);
        player.assignTeam(team.teamId());
        game.addPlayer(player);
        session.enterGame(gameId);
        deps.eventBus().publish(new PlayerJoinedRaidEvent(gameId, playerId));
        return Result.ok(game);
    }

    @Override
    public Result<RaidGame> leave(UUID playerId) {
        requireMainThread();
        Preconditions.checkNotNull(playerId, "playerId");
        Optional<PlayerSession> found = deps.sessions().find(playerId);
        Optional<RaidGameInstance> current = found.flatMap(PlayerSession::currentGame)
                .map(games::get);
        if (found.isEmpty() || current.isEmpty()) {
            return Result.fail("Player is not in a game");
        }
        RaidGameInstance game = current.get();
        game.removePlayer(playerId);
        found.get().leaveGame();
        deps.eventBus().publish(new PlayerLeftRaidEvent(game.id(), playerId));
        return Result.ok(game);
    }

    @Override
    public Result<RaidGame> start(RaidGameId id) {
        requireMainThread();
        Preconditions.checkNotNull(id, "id");
        RaidGameInstance game = games.get(id);
        if (game == null) {
            return Result.fail("Game not found");
        }
        if (game.state() != RaidGameState.WAITING) {
            return Result.fail("Game cannot start from state " + game.state());
        }
        Instant now = deps.timeSource().now();
        game.transitionTo(RaidGameState.STARTING, now);
        game.transitionTo(RaidGameState.RUNNING, now);
        deps.eventBus().publish(new RaidStartedEvent(id));
        Duration duration = Duration.ofSeconds(deps.settings().durationSeconds());
        game.setEndTask(deps.scheduler().runLater(duration, () -> autoStop(id)));
        return Result.ok(game);
    }

    @Override
    public Result<RaidGame> stop(RaidGameId id) {
        requireMainThread();
        Preconditions.checkNotNull(id, "id");
        RaidGameInstance game = games.get(id);
        if (game == null) {
            return Result.fail("Game not found");
        }
        Instant now = deps.timeSource().now();
        game.transitionTo(RaidGameState.ENDING, now);
        RaidResult result = buildResult(game, now);
        game.setResult(result);
        game.transitionTo(RaidGameState.FINISHED, now);
        deps.eventBus().publish(new RaidEndedEvent(id, result));
        destroy(game, now);
        return Result.ok(game);
    }

    @Override
    public void destroyAll() {
        requireMainThread();
        Instant now = deps.timeSource().now();
        for (RaidGameInstance game : List.copyOf(games.values())) {
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
    /// extraction record for the final [RaidResult]. Unknown games are ignored
    /// (the game may already have been destroyed).
    @Subscribe
    public void onPlayerExtracted(PlayerExtractedEvent event) {
        RaidGameInstance game = games.get(event.gameId());
        if (game == null) {
            return;
        }
        game.markExtracted(event.playerId());
        game.removePlayer(event.playerId());
        deps.sessions().find(event.playerId()).ifPresent(PlayerSession::leaveGame);
    }

    private void autoStop(RaidGameId id) {
        stop(id).error().ifPresent(message -> deps.logger()
                .warning("Auto-stop for game " + id.value() + " skipped: " + message));
    }

    private RaidResult buildResult(RaidGameInstance game, Instant endedAt) {
        Set<UUID> extracted = game.extractedPlayerIds();
        Set<UUID> remaining = game.players().stream()
                .map(RaidPlayer::playerId)
                .filter(playerId -> !extracted.contains(playerId))
                .collect(Collectors.toUnmodifiableSet());
        return new RaidResult(game.id(), endedAt, extracted, remaining);
    }

    private void destroy(RaidGameInstance game, Instant now) {
        game.cancelEndTask();
        for (RaidPlayer player : game.players()) {
            deps.sessions().find(player.playerId()).ifPresent(PlayerSession::leaveGame);
        }
        while (game.state() != RaidGameState.DESTROYED) {
            game.transitionTo(nextTowardDestroyed(game.state()), now);
        }
        games.remove(game.id());
    }

    /// Next legal step on the shortest path to DESTROYED; active games pass
    /// through ENDING and FINISHED because RUNNING cannot be destroyed directly.
    private static RaidGameState nextTowardDestroyed(RaidGameState state) {
        return switch (state) {
            case CREATED, WAITING, PREPARING, FINISHED, DESTROYED -> RaidGameState.DESTROYED;
            case STARTING, RUNNING -> RaidGameState.ENDING;
            case ENDING -> RaidGameState.FINISHED;
        };
    }

    private ExtractionPoint defaultExtractionPoint() {
        return new ExtractionPoint(DEFAULT_POINT_ID, "North Gate", ExtractionType.NORMAL,
                Duration.ofSeconds(deps.extraction().defaultDurationSeconds()), List.of(),
                ExtractionState.AVAILABLE);
    }

    private RaidGameId uniqueGameId() {
        while (true) {
            StringBuilder value = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                value.append(HEX_DIGITS.charAt(deps.random().nextInt(HEX_DIGITS.length())));
            }
            RaidGameId id = RaidGameId.of(value.toString());
            if (!games.containsKey(id)) {
                return id;
            }
        }
    }

    private void requireMainThread() {
        if (!deps.scheduler().isMainThread()) {
            throw new IllegalStateException("RaidGameService must be called from the main thread");
        }
    }
}
