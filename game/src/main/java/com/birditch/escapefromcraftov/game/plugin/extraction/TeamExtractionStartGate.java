package com.birditch.escapefromcraftov.game.plugin.extraction;

import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.extraction.ExtractionService;
import com.birditch.escapefromcraftov.api.extraction.ExtractionSession;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import java.util.Optional;
import java.util.UUID;

/// The sole game-plugin boundary for starting an extraction. An empty result
/// means the team route rejected the exit and the domain extraction service was
/// deliberately not invoked.
public final class TeamExtractionStartGate {

  private final ExtractionService extractions;
  private final TeamExtractionRoutes routes;

  public TeamExtractionStartGate(
      ExtractionService extractions, TeamExtractionRoutes routes) {
    this.extractions = Preconditions.checkNotNull(extractions, "extractions");
    this.routes = Preconditions.checkNotNull(routes, "routes");
  }

  public Optional<Result<ExtractionSession>> beginIfAssigned(
      CraftovGame game, UUID playerId, ExtractionPointId pointId) {
    Preconditions.checkNotNull(game, "game");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(pointId, "pointId");
    if (!routes.canUse(game, playerId, pointId)) return Optional.empty();
    return Optional.of(extractions.begin(playerId, game.id(), pointId));
  }
}
