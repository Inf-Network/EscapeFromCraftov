package com.birditch.escapefromcraftov.game.plugin.extraction;

import com.birditch.escapefromcraftov.api.extraction.ExtractionFailures;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.extraction.ExtractionService;
import com.birditch.escapefromcraftov.api.extraction.ExtractionSession;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import java.util.Optional;
import java.util.UUID;

/// Runtime-wide extraction facade. Every start is authorized against the
/// frozen team route; cancellation, session reads and settlement operations
/// remain exact delegate operations.
public final class RouteAwareExtractionService implements ExtractionService {

  private final ExtractionService delegate;
  private final CraftovGameService games;
  private final TeamExtractionRoutes routes;

  public RouteAwareExtractionService(
      ExtractionService delegate, CraftovGameService games, TeamExtractionRoutes routes) {
    this.delegate = Preconditions.checkNotNull(delegate, "delegate");
    this.games = Preconditions.checkNotNull(games, "games");
    this.routes = Preconditions.checkNotNull(routes, "routes");
  }

  @Override
  public Result<ExtractionSession> begin(
      UUID playerId, CraftovGameId gameId, ExtractionPointId pointId) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(pointId, "pointId");
    var game = games.find(gameId).orElse(null);
    if (game != null && !routes.canUse(game, playerId, pointId)) {
      return Result.fail(ExtractionFailures.ROUTE_NOT_ASSIGNED);
    }
    return delegate.begin(playerId, gameId, pointId);
  }

  @Override
  public boolean cancel(UUID playerId) {
    return delegate.cancel(playerId);
  }

  @Override
  public boolean completeSettlement(
      UUID playerId, CraftovGameId gameId, ExtractionPointId pointId) {
    return delegate.completeSettlement(playerId, gameId, pointId);
  }

  @Override
  public boolean failSettlement(
      UUID playerId, CraftovGameId gameId, ExtractionPointId pointId) {
    return delegate.failSettlement(playerId, gameId, pointId);
  }

  @Override
  public Optional<ExtractionSession> activeSession(UUID playerId) {
    return delegate.activeSession(playerId);
  }

  @Override
  public int activeCount() {
    return delegate.activeCount();
  }
}
