package com.birditch.escapefromcraftov.game.plugin.instance;

import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// Configurable [CraftovGameService] double for game-plugin tests: [#create]
/// mints sequential WAITING [FakeCraftovGame]s (or fails once a failure message
/// is set), [#addGame] seeds games without touching the create counter; the
/// game-driving operations fail loudly if touched by accident. Public so the
/// sibling test packages (`network`) can share it.
public final class FakeGameService implements CraftovGameService {

  private final Map<CraftovGameId, FakeCraftovGame> games = new LinkedHashMap<>();
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
  public FakeCraftovGame addGame(CraftovMapId mapId) {
    FakeCraftovGame game = newGame(mapId);
    games.put(game.id(), game);
    return game;
  }

  /// Every game in insertion order, as the concrete double for mutation.
  public List<FakeCraftovGame> created() {
    return List.copyOf(games.values());
  }

  @Override
  public Result<CraftovGame> create(CraftovMapId mapId) {
    Preconditions.checkNotNull(mapId, "mapId");
    createCalls++;
    if (failCreateWith != null) {
      return Result.fail(failCreateWith);
    }
    FakeCraftovGame game = newGame(mapId);
    games.put(game.id(), game);
    return Result.ok(game);
  }

  @Override
  public Optional<CraftovGame> find(CraftovGameId id) {
    return Optional.ofNullable(games.get(id));
  }

  @Override
  public Collection<CraftovGame> all() {
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
  public Result<CraftovGame> join(CraftovGameId gameId, UUID playerId) {
    throw new UnsupportedOperationException("join is not supported by FakeGameService");
  }

  @Override
  public Result<CraftovGame> leave(UUID playerId) {
    throw new UnsupportedOperationException("leave is not supported by FakeGameService");
  }

  @Override
  public Result<CraftovGame> start(CraftovGameId id) {
    throw new UnsupportedOperationException("start is not supported by FakeGameService");
  }

  @Override
  public Result<CraftovGame> stop(CraftovGameId id) {
    throw new UnsupportedOperationException("stop is not supported by FakeGameService");
  }

  private FakeCraftovGame newGame(CraftovMapId mapId) {
    return new FakeCraftovGame(CraftovGameId.of("game-" + nextId++), mapId);
  }
}
