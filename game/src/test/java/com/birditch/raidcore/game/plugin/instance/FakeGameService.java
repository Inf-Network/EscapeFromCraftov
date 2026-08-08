package com.birditch.raidcore.game.plugin.instance;

import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.result.Result;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// Configurable [RaidGameService] double for game-plugin tests: [#create]
/// mints sequential WAITING [FakeRaidGame]s (or fails once a failure message
/// is set), [#addGame] seeds games without touching the create counter; the
/// game-driving operations fail loudly if touched by accident. Public so the
/// sibling test packages (`network`) can share it.
public final class FakeGameService implements RaidGameService {

  private final Map<RaidGameId, FakeRaidGame> games = new LinkedHashMap<>();
  private int createCalls;
  private int nextId = 1;
  private @Nullable String failCreateWith;

  /// Makes every subsequent [#create] call fail with `message`.
  public void failCreateWith(String message) {
    this.failCreateWith = Preconditions.checkNotNull(message, "message");
  }

  /// Number of [#create] calls, successful or not.
  public int createCalls() {
    return createCalls;
  }

  /// Adds a game directly, bypassing [#create] and its call counter.
  public FakeRaidGame addGame(RaidMapId mapId) {
    FakeRaidGame game = newGame(mapId);
    games.put(game.id(), game);
    return game;
  }

  /// Every game in insertion order, as the concrete double for mutation.
  public List<FakeRaidGame> created() {
    return List.copyOf(games.values());
  }

  @Override
  public Result<RaidGame> create(RaidMapId mapId) {
    Preconditions.checkNotNull(mapId, "mapId");
    createCalls++;
    if (failCreateWith != null) {
      return Result.fail(failCreateWith);
    }
    FakeRaidGame game = newGame(mapId);
    games.put(game.id(), game);
    return Result.ok(game);
  }

  @Override
  public Optional<RaidGame> find(RaidGameId id) {
    return Optional.ofNullable(games.get(id));
  }

  @Override
  public Collection<RaidGame> all() {
    return Collections.unmodifiableCollection(games.values());
  }

  @Override
  public int activeCount() {
    return (int) games.values().stream().filter(game -> game.state().active()).count();
  }

  @Override
  public void destroyAll() {
    games.clear();
  }

  @Override
  public Result<RaidGame> join(RaidGameId gameId, UUID playerId) {
    throw new UnsupportedOperationException("join is not supported by FakeGameService");
  }

  @Override
  public Result<RaidGame> leave(UUID playerId) {
    throw new UnsupportedOperationException("leave is not supported by FakeGameService");
  }

  @Override
  public Result<RaidGame> start(RaidGameId id) {
    throw new UnsupportedOperationException("start is not supported by FakeGameService");
  }

  @Override
  public Result<RaidGame> stop(RaidGameId id) {
    throw new UnsupportedOperationException("stop is not supported by FakeGameService");
  }

  private FakeRaidGame newGame(RaidMapId mapId) {
    return new FakeRaidGame(RaidGameId.of("game-" + nextId++), mapId);
  }
}
