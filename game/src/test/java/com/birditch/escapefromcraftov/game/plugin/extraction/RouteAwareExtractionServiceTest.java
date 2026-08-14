package com.birditch.escapefromcraftov.game.plugin.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.extraction.ExtractionFailures;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.extraction.ExtractionService;
import com.birditch.escapefromcraftov.api.extraction.ExtractionSession;
import com.birditch.escapefromcraftov.api.extraction.ExtractionState;
import com.birditch.escapefromcraftov.api.extraction.ExtractionType;
import com.birditch.escapefromcraftov.api.game.CraftovExtractionArea;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovTeam;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.game.CraftovGameInstance;
import com.birditch.escapefromcraftov.game.CraftovMapRuntime;
import com.birditch.escapefromcraftov.core.service.ServiceRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RouteAwareExtractionServiceTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000011");
  private static final CraftovGameId GAME_ID = CraftovGameId.of("route-facade");
  private static final ExtractionPointId WEST = ExtractionPointId.of("west_gate");
  private static final ExtractionPointId EAST = ExtractionPointId.of("east_gate");

  @Test
  void registeredFacadeCannotBypassRouteAndDelegatesOtherOperations() {
    CraftovGameInstance game = game();
    TeamExtractionRoutes routes = new TeamExtractionRoutes();
    routes.freezeRoute(game, 1, -100, 0);
    CountingExtractionService delegate = new CountingExtractionService();
    ServiceRegistry services = new ServiceRegistry();
    services.register(ExtractionService.class, delegate);
    services.replace(ExtractionService.class, new RouteAwareExtractionService(
        services.require(ExtractionService.class), new SingleGameService(game), routes));
    ExtractionService facade = services.require(ExtractionService.class);

    assertEquals(ExtractionFailures.ROUTE_NOT_ASSIGNED,
        facade.begin(PLAYER, GAME_ID, WEST).error().orElseThrow());
    assertEquals(0, delegate.beginCalls);
    assertFalse(facade.begin(PLAYER, GAME_ID, EAST).isSuccess());
    assertEquals(1, delegate.beginCalls);
    assertTrue(facade.cancel(PLAYER));
    assertEquals(1, delegate.cancelCalls);
    assertEquals(7, facade.activeCount());
  }

  private static CraftovGameInstance game() {
    List<ExtractionPoint> points = List.of(point(WEST), point(EAST));
    List<CraftovExtractionArea> areas = List.of(
        new CraftovExtractionArea(WEST, "西门", -105, 60, -5, -95, 70, 5),
        new CraftovExtractionArea(EAST, "东门", 95, 60, -5, 105, 70, 5));
    CraftovGameInstance game = new CraftovGameInstance(GAME_ID, CraftovMapId.of("test_flat"),
        Instant.EPOCH, new CraftovMapRuntime(1, List.of(), List.of(), points, areas));
    game.transitionTo(CraftovGameState.WAITING, Instant.EPOCH);
    CraftovTeam team = game.teamForKeyOrCreate(PLAYER, 3);
    team.addMember(PLAYER);
    CraftovPlayer player = new CraftovPlayer(PLAYER, "FacadePlayer");
    player.assignTeam(team.teamId());
    game.addPlayer(player);
    return game;
  }

  private static ExtractionPoint point(ExtractionPointId id) {
    return new ExtractionPoint(id, id.value(), ExtractionType.NORMAL, Duration.ofSeconds(5),
        List.of(), ExtractionState.AVAILABLE);
  }

  private static final class CountingExtractionService implements ExtractionService {
    private int beginCalls;
    private int cancelCalls;

    @Override
    public Result<ExtractionSession> begin(
        UUID playerId, CraftovGameId gameId, ExtractionPointId pointId) {
      beginCalls++;
      return Result.fail("delegate");
    }

    @Override public boolean cancel(UUID playerId) { cancelCalls++; return true; }
    @Override public boolean completeSettlement(UUID playerId, CraftovGameId gameId,
        ExtractionPointId pointId) { return true; }
    @Override public boolean failSettlement(UUID playerId, CraftovGameId gameId,
        ExtractionPointId pointId) { return true; }
    @Override public Optional<ExtractionSession> activeSession(UUID playerId) {
      return Optional.empty();
    }
    @Override public int activeCount() { return 7; }
  }

  private record SingleGameService(CraftovGame game) implements CraftovGameService {
    @Override public Result<CraftovGame> create(CraftovMapId mapId) {
      return Result.fail("unsupported");
    }
    @Override public Optional<CraftovGame> find(CraftovGameId id) {
      return game.id().equals(id) ? Optional.of(game) : Optional.empty();
    }
    @Override public Collection<CraftovGame> all() { return List.of(game); }
    @Override public Result<CraftovGame> join(CraftovGameId id, UUID playerId) {
      return Result.fail("unsupported");
    }
    @Override public Result<CraftovGame> leave(UUID playerId) {
      return Result.fail("unsupported");
    }
    @Override public Result<CraftovGame> start(CraftovGameId id) {
      return Result.fail("unsupported");
    }
    @Override public Result<CraftovGame> stop(CraftovGameId id) {
      return Result.fail("unsupported");
    }
    @Override public void destroyAll() {}
    @Override public int activeCount() { return 1; }
  }
}
