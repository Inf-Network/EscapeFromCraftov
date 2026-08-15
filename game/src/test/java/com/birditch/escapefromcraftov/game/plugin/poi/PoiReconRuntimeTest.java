package com.birditch.escapefromcraftov.game.plugin.poi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovStartedEvent;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovPoi;
import com.birditch.escapefromcraftov.api.game.CraftovResult;
import com.birditch.escapefromcraftov.api.game.CraftovTeam;
import com.birditch.escapefromcraftov.api.testing.ManualScheduler;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

final class PoiReconRuntimeTest {

  private static final CraftovGameId GAME_ID = CraftovGameId.of("poi-runtime");
  private static final String WORLD = "craftov_poi_runtime";
  private static final CraftovPoi GROUND_POI = new CraftovPoi(
      "warehouse", "旧仓库", 4, 0, -61, 0, 0, -61, 0);

  @Test
  void teammateEntryCompletesOnceBeforeBestEffortNotificationsAndLeavesEnemyOpen() {
    CraftovPlayer entrant = teamPlayer(1);
    CraftovPlayer teammate = teamPlayer(1);
    CraftovPlayer enemy = teamPlayer(2);
    TestGame game = game(List.of(entrant, teammate, enemy), List.of(GROUND_POI));
    Harness harness = new Harness(game, true);
    harness.gateway.put(entrant.playerId(), presence(WORLD, 0.5, -60, 0.5, true));
    harness.gateway.put(teammate.playerId(), presence(WORLD, 20, -60, 20, true));
    harness.gateway.put(enemy.playerId(), presence(WORLD, 30, -60, 30, true));
    harness.gateway.throwOnSend.add(entrant.playerId());

    harness.startAndScan();

    assertTrue(harness.runtime.view(GAME_ID, entrant.playerId()).orElseThrow().completed());
    assertTrue(harness.runtime.view(GAME_ID, teammate.playerId()).orElseThrow().completed());
    assertFalse(harness.runtime.view(GAME_ID, enemy.playerId()).orElseThrow().completed());
    assertEquals(List.of(teammate.playerId()), harness.gateway.sent,
        "one failed send must not roll back completion or block the next teammate");
    assertEquals(1, harness.runtime.completedTeamCount());
    assertEquals(1, harness.scheduler.pendingCount(), "the scanner survives notification failure");

    harness.scheduler.advanceBy(PoiReconRuntime.SCAN_INTERVAL.multipliedBy(3));
    assertEquals(List.of(teammate.playerId()), harness.gateway.sent,
        "completion notification is first-wins and sent once");
  }

  @Test
  void missingDeployedCatalogKeyUsesAnEmbeddedChineseCompletionMessage() {
    CraftovPlayer player = player();
    Harness harness = new Harness(game(List.of(player), List.of(GROUND_POI)), true,
        new Messages(Map.of()));
    harness.gateway.put(player.playerId(), presence(WORLD, 0.5, -60, 0.5, true));

    harness.startAndScan();

    assertEquals(List.of(player.playerId()), harness.gateway.sent);
    assertEquals(Component.text("队伍侦察完成: ", NamedTextColor.GREEN)
            .append(Component.text(GROUND_POI.displayName(), NamedTextColor.YELLOW)),
        harness.gateway.sentMessages.getFirst());
  }

  @Test
  void playersWithoutTeamsHaveIndependentCompletionKeys() {
    CraftovPlayer first = player();
    CraftovPlayer second = player();
    Harness harness = new Harness(game(List.of(first, second), List.of(GROUND_POI)), true);
    harness.gateway.put(first.playerId(), presence(WORLD, 0.5, -60, 0.5, true));
    harness.gateway.put(second.playerId(), presence(WORLD, 10, -60, 10, true));

    harness.startAndScan();

    assertTrue(harness.runtime.view(GAME_ID, first.playerId()).orElseThrow().completed());
    assertFalse(harness.runtime.view(GAME_ID, second.playerId()).orElseThrow().completed());
    assertEquals(1, harness.runtime.completedTeamCount());
  }

  @Test
  void startBeforeWorldRegistrationFreezesTargetAndBecomesEligibleLater() {
    CraftovPlayer player = player();
    Harness harness = new Harness(game(List.of(player), List.of(GROUND_POI)), false);
    harness.gateway.put(player.playerId(), presence(WORLD, 0.5, -60, 0.5, true));
    harness.runtime.start();
    harness.runtime.onCraftovStarted(new CraftovStartedEvent(GAME_ID));

    harness.scheduler.advanceBy(Duration.ZERO);
    assertEquals(1, harness.runtime.activeGameCount());
    assertEquals(0, harness.runtime.completedTeamCount());
    assertTrue(harness.runtime.view(GAME_ID, player.playerId()).isEmpty());

    harness.index.register(GAME_ID, WORLD);
    harness.scheduler.advanceBy(PoiReconRuntime.SCAN_INTERVAL);
    assertTrue(harness.runtime.view(GAME_ID, player.playerId()).orElseThrow().completed());
  }

  @Test
  void nonRunningGameCannotActivateOrExposeAQuery() {
    CraftovPlayer player = player();
    TestGame game = game(List.of(player), List.of(GROUND_POI));
    game.state(CraftovGameState.WAITING);
    Harness harness = new Harness(game, true);
    harness.gateway.put(player.playerId(), presence(WORLD, 0.5, -60, 0.5, true));
    harness.runtime.start();
    harness.runtime.onCraftovStarted(new CraftovStartedEvent(GAME_ID));
    harness.scheduler.advanceBy(Duration.ZERO);

    assertEquals(0, harness.runtime.activeGameCount());
    assertTrue(harness.runtime.view(GAME_ID, player.playerId()).isEmpty());
  }

  @Test
  void wrongWorldCombatStateExtractionAndPhysicalModesFailClosed() {
    CraftovPlayer wrongWorld = player();
    CraftovPlayer combatDead = player();
    CraftovPlayer extracted = player();
    extracted.markExtracted();
    CraftovPlayer bukkitDead = player();
    CraftovPlayer invalid = player();
    CraftovPlayer creative = player();
    CraftovPlayer spectator = player();
    CraftovPlayer offline = player();
    List<CraftovPlayer> players = List.of(wrongWorld, combatDead, extracted, bukkitDead,
        invalid, creative, spectator, offline);
    Harness harness = new Harness(game(players, List.of(GROUND_POI)), true);
    players.forEach(value -> harness.gateway.put(value.playerId(),
        presence(WORLD, 0.5, -60, 0.5, true)));
    harness.gateway.put(wrongWorld.playerId(), presence("other_world", 0.5, -60, 0.5, true));
    harness.alive.remove(combatDead.playerId());
    harness.gateway.put(bukkitDead.playerId(), physicalPresence(GameMode.SURVIVAL,
        true, true, true));
    harness.gateway.put(invalid.playerId(), physicalPresence(GameMode.SURVIVAL,
        true, false, false));
    harness.gateway.put(creative.playerId(), physicalPresence(GameMode.CREATIVE,
        true, true, false));
    harness.gateway.put(spectator.playerId(), physicalPresence(GameMode.SPECTATOR,
        true, true, false));
    harness.gateway.put(offline.playerId(), physicalPresence(GameMode.SURVIVAL,
        false, true, false));

    harness.startAndScan();

    assertEquals(0, harness.runtime.completedTeamCount());
    players.forEach(value -> assertTrue(harness.runtime.view(GAME_ID, value.playerId()).isEmpty(),
        () -> "ineligible player leaked into query: " + value.playerId()));
    assertTrue(harness.runtime.view(CraftovGameId.of("wrong-game"), wrongWorld.playerId()).isEmpty());
  }

  @Test
  void scannerHasAnExplicitRoundRobinPlayerBudget() {
    List<CraftovPlayer> players = new ArrayList<>();
    for (int index = 0; index < PoiReconRuntime.MAX_PLAYER_CHECKS_PER_TICK + 1; index++) {
      CraftovPlayer player = player();
      players.add(player);
    }
    Harness harness = new Harness(game(players, List.of(GROUND_POI)), true);
    players.forEach(player -> harness.gateway.put(player.playerId(),
        presence(WORLD, 50, -60, 50, true)));
    harness.runtime.start();
    harness.runtime.onCraftovStarted(new CraftovStartedEvent(GAME_ID));

    harness.scheduler.advanceBy(Duration.ZERO);
    assertEquals(PoiReconRuntime.MAX_PLAYER_CHECKS_PER_TICK, harness.gateway.snapshotCalls);
    assertEquals(0, harness.gateway.calls(players.get(players.size() - 1).playerId()));

    harness.scheduler.advanceBy(PoiReconRuntime.SCAN_INTERVAL);
    assertTrue(harness.gateway.calls(players.get(players.size() - 1).playerId()) > 0,
        "round robin must reach the deferred candidate on the next bounded scan");
  }

  @Test
  void noPoiIsANoopAndEndThenCloseClearAllStateAndTheUniqueTask() {
    CraftovPlayer player = player();
    Harness empty = new Harness(game(List.of(player), List.of()), true);
    empty.runtime.start();
    empty.runtime.start();
    empty.runtime.onCraftovStarted(new CraftovStartedEvent(GAME_ID));
    empty.scheduler.advanceBy(Duration.ZERO);
    assertEquals(0, empty.runtime.activeGameCount());
    assertTrue(empty.runtime.view(GAME_ID, player.playerId()).isEmpty());
    assertEquals(1, empty.scheduler.pendingCount());
    empty.runtime.close();
    assertEquals(0, empty.scheduler.pendingCount());

    Harness active = new Harness(game(List.of(player), List.of(GROUND_POI)), true);
    active.gateway.put(player.playerId(), presence(WORLD, 0.5, -60, 0.5, true));
    active.startAndScan();
    active.runtime.onCraftovEnded(new CraftovEndedEvent(GAME_ID,
        new CraftovResult(GAME_ID, Instant.EPOCH, Set.of(), Set.of(player.playerId()))));
    assertEquals(0, active.runtime.activeGameCount());
    assertEquals(0, active.runtime.completedTeamCount());
    assertTrue(active.runtime.view(GAME_ID, player.playerId()).isEmpty());
    assertEquals(1, active.scheduler.pendingCount(), "end keeps the one global scanner alive");
    active.runtime.close();
    active.runtime.close();
    assertEquals(0, active.scheduler.pendingCount());
  }

  @Test
  void queryTruncatesNamesAndNeverExposesUnboundedDistance() {
    CraftovPlayer player = player();
    String longName = "这是一个非常非常非常非常非常非常非常长的侦察目标名称";
    CraftovPoi extreme = new CraftovPoi("extreme", longName, 4,
        Integer.MAX_VALUE, 0, Integer.MAX_VALUE,
        Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
    Harness harness = new Harness(game(List.of(player), List.of(extreme)), true);
    harness.gateway.put(player.playerId(), presence(WORLD,
        Integer.MIN_VALUE, 0, Integer.MIN_VALUE, true));
    harness.runtime.start();
    harness.runtime.onCraftovStarted(new CraftovStartedEvent(GAME_ID));

    PoiReconLookup.View view = harness.runtime.view(GAME_ID, player.playerId()).orElseThrow();
    assertEquals(PoiReconRuntime.MAXIMUM_DISPLAY_NAME_CODE_POINTS,
        view.displayName().codePointCount(0, view.displayName().length()));
    assertTrue(view.displayName().endsWith("…"));
    assertEquals("9999m+", view.distanceLabel());
    assertTrue(view.distanceCapped());
  }

  private static TestGame game(List<CraftovPlayer> players, List<CraftovPoi> pois) {
    return new TestGame(GAME_ID, players, pois);
  }

  private static CraftovPlayer player() {
    UUID id = UUID.randomUUID();
    return new CraftovPlayer(id, id.toString());
  }

  private static CraftovPlayer teamPlayer(int teamId) {
    CraftovPlayer player = player();
    player.assignTeam(teamId);
    return player;
  }

  private static PoiReconRuntime.Presence presence(String world, double x, double y, double z,
      boolean onGround) {
    return new PoiReconRuntime.Presence(world, x, y, z,
        true, onGround, true, false, GameMode.SURVIVAL);
  }

  private static PoiReconRuntime.Presence physicalPresence(GameMode mode,
      boolean online, boolean valid, boolean dead) {
    return new PoiReconRuntime.Presence(WORLD, 0.5, -60, 0.5,
        online, true, valid, dead, mode);
  }

  private static final class Harness {
    private final TestGame game;
    private final ManualScheduler scheduler = new ManualScheduler();
    private final InstanceWorldIndex index = new InstanceWorldIndex();
    private final FakePlayerGateway gateway = new FakePlayerGateway();
    private final Set<UUID> alive = new HashSet<>();
    private final PoiReconRuntime runtime;

    private Harness(TestGame game, boolean registerWorld) {
      this(game, registerWorld, new Messages(Map.of("poi.recon-completed",
          "<green>队伍侦察完成:</green> <yellow><poi></yellow>")));
    }

    private Harness(TestGame game, boolean registerWorld, Messages messages) {
      this.game = game;
      game.players().forEach(player -> alive.add(player.playerId()));
      if (registerWorld) index.register(game.id(), WORLD);
      runtime = new PoiReconRuntime(new TestGameService(List.of(game)), index, scheduler,
          alive::contains, gateway, messages,
          Logger.getLogger(PoiReconRuntimeTest.class.getName()));
    }

    private void startAndScan() {
      runtime.start();
      runtime.onCraftovStarted(new CraftovStartedEvent(game.id()));
      scheduler.advanceBy(Duration.ZERO);
    }
  }

  private static final class FakePlayerGateway implements PoiReconRuntime.PlayerGateway {
    private final Map<UUID, PoiReconRuntime.Presence> presences = new HashMap<>();
    private final Map<UUID, Integer> calls = new HashMap<>();
    private final Set<UUID> throwOnSend = new HashSet<>();
    private final List<UUID> sent = new ArrayList<>();
    private final List<Component> sentMessages = new ArrayList<>();
    private int snapshotCalls;

    private void put(UUID playerId, PoiReconRuntime.Presence presence) {
      presences.put(playerId, presence);
    }

    private int calls(UUID playerId) {
      return calls.getOrDefault(playerId, 0);
    }

    @Override
    public Optional<PoiReconRuntime.Presence> snapshot(UUID playerId) {
      snapshotCalls++;
      calls.merge(playerId, 1, Integer::sum);
      return Optional.ofNullable(presences.get(playerId));
    }

    @Override
    public void send(UUID playerId, Component message) {
      if (throwOnSend.contains(playerId)) throw new IllegalStateException("synthetic send failure");
      sent.add(playerId);
      sentMessages.add(message);
    }
  }

  private static final class TestGame implements CraftovGame {
    private final CraftovGameId id;
    private final List<CraftovPlayer> players;
    private final List<CraftovPoi> pois;
    private CraftovGameState state = CraftovGameState.RUNNING;

    private TestGame(CraftovGameId id, List<CraftovPlayer> players, List<CraftovPoi> pois) {
      this.id = id;
      this.players = List.copyOf(players);
      this.pois = List.copyOf(pois);
    }

    @Override public CraftovGameId id() { return id; }
    @Override public CraftovMapId mapId() { return CraftovMapId.of("test-flat"); }
    @Override public long mapRevision() { return 18; }
    @Override public List<CraftovPoi> pois() { return pois; }
    private void state(CraftovGameState state) { this.state = state; }
    @Override public CraftovGameState state() { return state; }
    @Override public Collection<CraftovPlayer> players() { return players; }
    @Override public Collection<CraftovTeam> teams() { return List.of(); }
    @Override public List<ExtractionPoint> extractionPoints() { return List.of(); }
    @Override public Instant createdAt() { return Instant.EPOCH; }
    @Override public Optional<Instant> startedAt() { return Optional.of(Instant.EPOCH); }
    @Override public Optional<CraftovResult> result() { return Optional.empty(); }
    @Override public int playerCount() { return players.size(); }
  }

  private record TestGameService(List<TestGame> games) implements CraftovGameService {
    @Override public Result<CraftovGame> create(CraftovMapId mapId) {
      return Result.fail("unsupported");
    }
    @Override public Optional<CraftovGame> find(CraftovGameId id) {
      return games.stream().filter(game -> game.id().equals(id))
          .map(CraftovGame.class::cast).findFirst();
    }
    @Override public Collection<CraftovGame> all() { return List.copyOf(games); }
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
    @Override public int activeCount() { return games.size(); }
  }
}
