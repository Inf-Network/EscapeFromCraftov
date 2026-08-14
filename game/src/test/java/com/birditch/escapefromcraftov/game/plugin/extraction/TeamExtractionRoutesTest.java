package com.birditch.escapefromcraftov.game.plugin.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.extraction.ExtractionCondition;
import com.birditch.escapefromcraftov.api.extraction.ExtractionContext;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.extraction.ExtractionService;
import com.birditch.escapefromcraftov.api.extraction.ExtractionSession;
import com.birditch.escapefromcraftov.api.extraction.ExtractionState;
import com.birditch.escapefromcraftov.api.extraction.ExtractionType;
import com.birditch.escapefromcraftov.api.game.CraftovExtractionArea;
import com.birditch.escapefromcraftov.api.game.CraftovExtractionRouteScope;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovSpawnLocation;
import com.birditch.escapefromcraftov.api.game.CraftovTeam;
import com.birditch.escapefromcraftov.game.CraftovGameInstance;
import com.birditch.escapefromcraftov.game.CraftovMapRuntime;
import com.birditch.escapefromcraftov.common.result.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamExtractionRoutesTest {

  private static final CraftovGameId GAME_ID = CraftovGameId.of("route-game");
  private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID EVE = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final ExtractionPointId WEST = ExtractionPointId.of("west_gate");
  private static final ExtractionPointId EAST = ExtractionPointId.of("east_gate");

  @Test
  void freezesTeamSpawnAndAllowsOnlyThatTeamsOppositeExit() {
    CraftovGameInstance game = game(true);
    addTeam(game, 1, ALICE, BOB);
    addTeam(game, 2, EVE);
    TeamExtractionRoutes routes = new TeamExtractionRoutes();
    CraftovSpawnLocation westSpawn = spawn("west_spawn", -100);
    CraftovSpawnLocation ignored = spawn("ignored", 100);

    assertSame(westSpawn, routes.rememberSpawn(GAME_ID, 1, westSpawn));
    assertSame(westSpawn, routes.rememberSpawn(GAME_ID, 1, ignored));
    assertEquals(EAST, routes.freezeRoute(game, 1, -100, 0).orElseThrow());
    // A later teleport cannot rewrite the team's route.
    assertEquals(EAST, routes.freezeRoute(game, 1, 100, 0).orElseThrow());

    assertTrue(routes.canUse(game, ALICE, EAST));
    assertTrue(routes.canUse(game, BOB, EAST));
    assertFalse(routes.canUse(game, ALICE, WEST));
    assertEquals(List.of(EAST), routes.visibleAreas(game, BOB).stream()
        .map(CraftovExtractionArea::pointId).toList());

    routes.freezeRoute(game, 2, 100, 0);
    assertTrue(routes.canUse(game, EVE, WEST));
    assertFalse(routes.canUse(game, EVE, EAST));
  }

  @Test
  void missingLegacySpatialDataFallsBackAndAbandonedTeamStateIsPruned() {
    CraftovGameInstance game = game(false);
    addTeam(game, 1, ALICE);
    TeamExtractionRoutes routes = new TeamExtractionRoutes();
    routes.rememberSpawn(GAME_ID, 1, spawn("legacy", 0));

    assertTrue(routes.freezeRoute(game, 1, 0, 0).isEmpty());
    assertTrue(routes.canUse(game, ALICE, WEST));
    assertTrue(routes.canUse(game, ALICE, EAST));

    game.removePlayer(ALICE);
    routes.prune(game);
    assertTrue(routes.spawn(GAME_ID, 1).isEmpty());
  }

  @Test
  void validMapAuthoredRouteOverridesTheSpatialFallback() {
    CraftovGameInstance game = game(true);
    addTeam(game, 1, ALICE);
    TeamExtractionRoutes routes = new TeamExtractionRoutes();

    assertEquals(java.util.Set.of(WEST, EAST), routes.freezeRoute(
        game, 1, -100, 0, java.util.Set.of(WEST, EAST)));
    assertEquals(WEST, routes.assignedPoint(game, ALICE).orElseThrow());
    assertTrue(routes.canUse(game, ALICE, WEST));
    assertTrue(routes.canUse(game, ALICE, EAST));
  }

  @Test
  void resolverFallbackReplacesPreviouslyRememberedAuthoredSpawn() {
    TeamExtractionRoutes routes = new TeamExtractionRoutes();
    routes.rememberSpawn(GAME_ID, 1, spawn("broken_authored", 900));

    var fallback = routes.rememberResolvedSpawn(
        GAME_ID, 1, 1.5, 64, -2.5, 90, 0, true);

    assertEquals(1.5, fallback.x());
    assertEquals("fallback_team_1", routes.spawn(GAME_ID, 1).orElseThrow().id());
    assertEquals("fallback_team_1", routes.spawn(GAME_ID, 1).orElseThrow().routeGroup());
    assertEquals(fallback, routes.resolvedSpawn(GAME_ID, 1).orElseThrow());
    assertTrue(routes.reservedAuthoredSpawnIds(GAME_ID).isEmpty());
    assertEquals("fallback_team_1",
        routes.rememberSpawn(GAME_ID, 1, spawn("later_retry", -900)).id());
  }

  @Test
  void firstSuccessfulResolvedLocationCannotBeReplacedByLaterFallback() {
    TeamExtractionRoutes routes = new TeamExtractionRoutes();
    routes.rememberSpawn(GAME_ID, 1, spawn("authored", 100));
    var success = routes.rememberResolvedSpawn(
        GAME_ID, 1, 101.5, 70, 4.5, 0, 0, false);

    var laterFallback = routes.rememberResolvedSpawn(
        GAME_ID, 1, 0.5, 64, 0.5, 90, 0, true);

    assertEquals(success, laterFallback);
    assertEquals(success, routes.resolvedSpawn(GAME_ID, 1).orElseThrow());
    assertFalse(success.fallback());
    assertEquals("authored", routes.spawn(GAME_ID, 1).orElseThrow().id());
  }

  @Test
  void eventCompletedExitIsNotTreatedAsAReliablePrimary() {
    ExtractionPointId fixedId = ExtractionPointId.of("fixed_gate");
    ExtractionPointId conditionalId = ExtractionPointId.of("conditional_gate");
    ExtractionCondition condition = new EventCompletedExtractionCondition(
        "overseer", "高价值目标", (_, _) -> true);
    List<ExtractionPoint> points = List.of(
        point(fixedId),
        new ExtractionPoint(conditionalId, "条件远门", ExtractionType.CONDITIONAL,
            Duration.ofSeconds(5), List.of(condition), ExtractionState.AVAILABLE));
    List<CraftovExtractionArea> areas = List.of(
        new CraftovExtractionArea(fixedId, "固定近门", 10, 60, -2, 12, 70, 2),
        new CraftovExtractionArea(conditionalId, "条件远门", 500, 60, -2, 502, 70, 2));
    CraftovGameInstance game = new CraftovGameInstance(GAME_ID, CraftovMapId.of("test_flat"),
        Instant.EPOCH, new CraftovMapRuntime(1, List.of(), List.of(), points, areas));
    game.transitionTo(CraftovGameState.WAITING, Instant.EPOCH);
    addTeam(game, 1, ALICE);
    TeamExtractionRoutes routes = new TeamExtractionRoutes();

    assertEquals(fixedId, routes.freezeRoute(game, 1, 0, 0).orElseThrow());
  }

  @Test
  void authoredSpawnGroupReceivesMatchingAndGlobalExitsOnly() {
    ExtractionPointId northId = ExtractionPointId.of("north_route");
    ExtractionPointId southId = ExtractionPointId.of("south_route");
    ExtractionPointId globalId = ExtractionPointId.of("global_route");
    List<ExtractionPoint> points = List.of(point(northId), point(southId), point(globalId));
    List<CraftovExtractionArea> areas = List.of(
        new CraftovExtractionArea(northId, "North", 10, 60, 0, 12, 70, 2,
            new CraftovExtractionRouteScope(false, java.util.Set.of("north"))),
        new CraftovExtractionArea(southId, "South", -12, 60, 0, -10, 70, 2,
            new CraftovExtractionRouteScope(false, java.util.Set.of("south"))),
        new CraftovExtractionArea(globalId, "Global", 0, 60, 100, 2, 70, 102,
            new CraftovExtractionRouteScope(true, java.util.Set.of())));
    CraftovGameInstance game = new CraftovGameInstance(GAME_ID, CraftovMapId.of("test_flat"),
        Instant.EPOCH, new CraftovMapRuntime(1, List.of(), List.of(), points, areas));
    game.transitionTo(CraftovGameState.WAITING, Instant.EPOCH);
    addTeam(game, 1, ALICE);
    TeamExtractionRoutes routes = new TeamExtractionRoutes();
    routes.rememberSpawn(GAME_ID, 1, new CraftovSpawnLocation("spawn_north", 0, 64, 0,
        0, 0, "players", 1, null, "north"));

    routes.freezeRoute(game, 1, 0, 0);

    assertEquals(java.util.Set.of(northId, globalId), routes.assignedPoints(game, ALICE));
    assertTrue(routes.canUse(game, ALICE, northId));
    assertTrue(routes.canUse(game, ALICE, globalId));
    assertFalse(routes.canUse(game, ALICE, southId));
  }

  @Test
  void authoredRoutesDenyEveryExitUntilTheTeamRouteIsFrozen() {
    ExtractionPointId northId = ExtractionPointId.of("north_locked");
    List<ExtractionPoint> points = List.of(point(northId));
    List<CraftovExtractionArea> areas = List.of(new CraftovExtractionArea(
        northId, "North", 10, 60, 0, 12, 70, 2,
        new CraftovExtractionRouteScope(false, java.util.Set.of("north"))));
    CraftovGameInstance game = new CraftovGameInstance(GAME_ID, CraftovMapId.of("test_flat"),
        Instant.EPOCH, new CraftovMapRuntime(1, List.of(), List.of(), points, areas));
    game.transitionTo(CraftovGameState.WAITING, Instant.EPOCH);
    addTeam(game, 1, ALICE);
    TeamExtractionRoutes routes = new TeamExtractionRoutes();

    assertFalse(routes.canUse(game, ALICE, northId));
    assertTrue(routes.visibleAreas(game, ALICE).isEmpty());
  }

  @Test
  void missingAuthoredGroupStillGetsReliableUnscopedFallback() {
    ExtractionPointId northId = ExtractionPointId.of("north_only");
    ExtractionPointId fallbackId = ExtractionPointId.of("safe_fallback");
    List<ExtractionPoint> points = List.of(point(northId), point(fallbackId));
    List<CraftovExtractionArea> areas = List.of(
        new CraftovExtractionArea(northId, "North", 500, 60, 0, 502, 70, 2,
            new CraftovExtractionRouteScope(false, java.util.Set.of("north"))),
        new CraftovExtractionArea(fallbackId, "Safe", 10, 60, 0, 12, 70, 2));
    CraftovGameInstance game = new CraftovGameInstance(GAME_ID, CraftovMapId.of("test_flat"),
        Instant.EPOCH, new CraftovMapRuntime(1, List.of(), List.of(), points, areas));
    game.transitionTo(CraftovGameState.WAITING, Instant.EPOCH);
    addTeam(game, 1, ALICE);
    TeamExtractionRoutes routes = new TeamExtractionRoutes();
    routes.rememberSpawn(GAME_ID, 1, new CraftovSpawnLocation("spawn_south", 0, 64, 0,
        0, 0, "players", 1, null, "south"));

    assertEquals(fallbackId, routes.freezeRoute(game, 1, 0, 0).orElseThrow());
    assertEquals(java.util.Set.of(fallbackId), routes.assignedPoints(game, ALICE));
  }

  @Test
  void automaticRouteWithoutReliableExitDegradesToOriginalConditionChecks() {
    ExtractionPointId conditionalId = ExtractionPointId.of("conditional_only");
    ExtractionCondition condition = new ExtractionCondition() {
      @Override public String describe() { return "测试条件"; }
      @Override public boolean isSatisfied(ExtractionContext context) { return true; }
    };
    List<ExtractionPoint> points = List.of(new ExtractionPoint(conditionalId, "Conditional",
        ExtractionType.CONDITIONAL, Duration.ofSeconds(5), List.of(condition),
        ExtractionState.AVAILABLE));
    List<CraftovExtractionArea> areas = List.of(new CraftovExtractionArea(
        conditionalId, "Conditional", 100, 60, 0, 102, 70, 2,
        new CraftovExtractionRouteScope(false, java.util.Set.of("north"))));
    CraftovGameInstance game = new CraftovGameInstance(GAME_ID, CraftovMapId.of("test_flat"),
        Instant.EPOCH, new CraftovMapRuntime(1, List.of(), List.of(), points, areas));
    game.transitionTo(CraftovGameState.WAITING, Instant.EPOCH);
    addTeam(game, 1, ALICE);
    TeamExtractionRoutes routes = new TeamExtractionRoutes();
    routes.rememberSpawn(GAME_ID, 1, new CraftovSpawnLocation("spawn_north", 0, 64, 0,
        0, 0, "players", 1, null, "north"));

    assertTrue(routes.freezeRoute(game, 1, 0, 0).isEmpty());
    assertTrue(routes.canUse(game, ALICE, conditionalId));
    assertEquals(List.of(conditionalId), routes.visibleAreas(game, ALICE).stream()
        .map(CraftovExtractionArea::pointId).toList());
  }

  @Test
  void startGateNeverCallsDomainServiceForAnotherTeamsExit() {
    CraftovGameInstance game = game(true);
    addTeam(game, 1, ALICE);
    TeamExtractionRoutes routes = new TeamExtractionRoutes();
    routes.freezeRoute(game, 1, -100, 0);
    CountingExtractionService delegate = new CountingExtractionService();
    TeamExtractionStartGate gate = new TeamExtractionStartGate(delegate, routes);

    assertTrue(gate.beginIfAssigned(game, ALICE, WEST).isEmpty());
    assertEquals(0, delegate.beginCalls);
    assertTrue(gate.beginIfAssigned(game, ALICE, EAST).isPresent());
    assertEquals(1, delegate.beginCalls);
  }

  private static CraftovGameInstance game(boolean spatialAreas) {
    List<ExtractionPoint> points = List.of(point(WEST), point(EAST));
    List<CraftovExtractionArea> areas = spatialAreas ? List.of(
        new CraftovExtractionArea(WEST, "西门", -105, 60, -5, -95, 70, 5),
        new CraftovExtractionArea(EAST, "东门", 95, 60, -5, 105, 70, 5)) : List.of();
    CraftovMapRuntime runtime = new CraftovMapRuntime(
        1, List.of(), List.of(), points, areas);
    CraftovGameInstance game = new CraftovGameInstance(
        GAME_ID, CraftovMapId.of("test_flat"), Instant.EPOCH, runtime);
    game.transitionTo(CraftovGameState.WAITING, Instant.EPOCH);
    return game;
  }

  private static void addTeam(
      CraftovGameInstance game, int expectedId, UUID... playerIds) {
    CraftovTeam team = game.teamForKeyOrCreate(new UUID(0, expectedId), 3);
    assertEquals(expectedId, team.teamId());
    for (UUID playerId : playerIds) {
      CraftovPlayer player = new CraftovPlayer(playerId, "player-" + playerId);
      assertTrue(team.addMember(playerId));
      player.assignTeam(team.teamId());
      game.addPlayer(player);
    }
  }

  private static ExtractionPoint point(ExtractionPointId id) {
    return new ExtractionPoint(id, id.value(), ExtractionType.NORMAL, Duration.ofSeconds(5),
        List.of(), ExtractionState.AVAILABLE);
  }

  private static CraftovSpawnLocation spawn(String id, int x) {
    return new CraftovSpawnLocation(id, x, 64, 0, 0, 0);
  }

  private static final class CountingExtractionService implements ExtractionService {

    private int beginCalls;

    @Override
    public Result<ExtractionSession> begin(
        UUID playerId, CraftovGameId gameId, ExtractionPointId pointId) {
      beginCalls++;
      return Result.fail("test delegate");
    }

    @Override public boolean cancel(UUID playerId) { return false; }

    @Override
    public boolean completeSettlement(
        UUID playerId, CraftovGameId gameId, ExtractionPointId pointId) {
      return false;
    }

    @Override
    public boolean failSettlement(
        UUID playerId, CraftovGameId gameId, ExtractionPointId pointId) {
      return false;
    }

    @Override public Optional<ExtractionSession> activeSession(UUID playerId) {
      return Optional.empty();
    }

    @Override public int activeCount() { return 0; }
  }
}
