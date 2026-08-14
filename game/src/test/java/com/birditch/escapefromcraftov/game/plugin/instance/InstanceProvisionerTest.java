package com.birditch.escapefromcraftov.game.plugin.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.api.network.ServerId;
import com.birditch.escapefromcraftov.api.network.ProvisionRequest;
import com.birditch.escapefromcraftov.api.testing.ManualScheduler;
import com.birditch.escapefromcraftov.api.testing.MutableTimeSource;
import com.birditch.escapefromcraftov.core.config.EscapeFromCraftovConfig;
import com.birditch.escapefromcraftov.game.plugin.GameServerConfig;
import java.time.Duration;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InstanceProvisioner")
class InstanceProvisionerTest {

  private static final Duration PASS_INTERVAL = Duration.ofSeconds(5);
  private static final String OWN_SERVER_ID = "game-01";

  private FakeGameService games;
  private ManualScheduler scheduler;
  private MutableTimeSource timeSource;

  @BeforeEach
  void setUp() {
    games = new FakeGameService();
    scheduler = new ManualScheduler();
    timeSource = new MutableTimeSource(0);
  }

  private InstanceProvisioner provisioner(int minReadyInstances, int maxInstances) {
    GameServerConfig config = new GameServerConfig(
        ServerId.of(OWN_SERVER_ID), "craftov-01", CraftovMapId.of("debug_zone"),
        2, 15, minReadyInstances, "maps", false, "lobby", 2);
    EscapeFromCraftovConfig.GameSettings coreSettings =
        new EscapeFromCraftovConfig.GameSettings(12, 3, 1800, maxInstances);
    return new InstanceProvisioner(new ProvisionerDeps(
        games,
        coreSettings,
        config,
        _ -> true,
        timeSource,
        scheduler,
        Logger.getLogger(InstanceProvisionerTest.class.getName())));
  }

  @Nested
  @DisplayName("start")
  class Start {

    @Test
    @DisplayName("provisions up to min-ready immediately")
    void provisionsUpToMinReadyImmediately() {
      provisioner(2, 4).start();

      assertEquals(2, games.all().size());
      assertTrue(games.created().stream()
          .allMatch(game -> game.state() == CraftovGameState.WAITING));
      assertEquals(CraftovMapId.of("debug_zone"), games.created().getFirst().mapId());
    }

    @Test
    @DisplayName("creates nothing when min-ready is zero")
    void createsNothingWhenMinReadyIsZero() {
      provisioner(0, 4).start();

      assertEquals(0, games.createCalls());
    }

    @Test
    @DisplayName("never exceeds max-instances")
    void neverExceedsMaxInstances() {
      InstanceProvisioner provisioner = provisioner(5, 3);
      provisioner.start();

      assertEquals(3, games.all().size());

      scheduler.advanceBy(PASS_INTERVAL);
      assertEquals(3, games.all().size());
    }

    @Test
    @DisplayName("rejects a second start")
    void rejectsSecondStart() {
      InstanceProvisioner provisioner = provisioner(1, 4);
      provisioner.start();

      assertThrows(IllegalStateException.class, provisioner::start);
    }
  }

  @Nested
  @DisplayName("timer pass")
  class TimerPass {

    @Test
    @DisplayName("tops back up after an instance starts")
    void topsBackUpAfterInstanceStarts() {
      provisioner(1, 4).start();
      assertEquals(1, games.all().size());
      games.created().getFirst().setState(CraftovGameState.RUNNING);

      scheduler.advanceBy(PASS_INTERVAL);

      assertEquals(2, games.all().size());
      assertEquals(CraftovGameState.WAITING, games.created().getLast().state());
    }

    @Test
    @DisplayName("halts the pass on a failure Result and retries next pass")
    void haltsPassOnFailureResult() {
      games.failCreateWith("Instance limit reached (4)");
      provisioner(3, 10).start();

      assertEquals(1, games.createCalls());
      assertEquals(0, games.all().size());

      scheduler.advanceBy(PASS_INTERVAL);
      assertEquals(2, games.createCalls());
    }
  }

  @Nested
  @DisplayName("onProvisionRequest")
  class OnProvisionRequest {

    @Test
    @DisplayName("own serverId triggers an immediate pass")
    void ownServerIdTriggersImmediatePass() {
      InstanceProvisioner provisioner = provisioner(1, 4);
      provisioner.start();
      games.created().getFirst().setState(CraftovGameState.RUNNING);

      provisioner.onProvisionRequest(new ProvisionRequest(
          ServerId.of(OWN_SERVER_ID), CraftovMapId.of("debug_zone")));

      assertEquals(2, games.all().size());
    }

    @Test
    @DisplayName("a non-default pool gets its own correctly tagged room")
    void provisionsExactRequestedPool() {
      InstanceProvisioner provisioner = provisioner(1, 4);
      provisioner.start();

      provisioner.onProvisionRequest(new ProvisionRequest(
          ServerId.of(OWN_SERVER_ID), CraftovMapId.of("debug_zone"),
          RaidMode.CONFIDENTIAL, RaidDifficulty.HARD));

      assertEquals(2, games.all().size());
      assertEquals(RaidMode.CONFIDENTIAL, games.created().getLast().mode());
      assertEquals(RaidDifficulty.HARD, games.created().getLast().difficulty());
    }

    @Test
    @DisplayName("a canonical tier is not satisfied by a legacy room with the same pair")
    void provisionsCanonicalTierSeparatelyFromLegacyPool() {
      InstanceProvisioner provisioner = provisioner(1, 4);
      provisioner.start();
      RaidTierId tierId = RaidTierId.of("normal");

      provisioner.onProvisionRequest(new ProvisionRequest(
          ServerId.of(OWN_SERVER_ID), CraftovMapId.of("debug_zone"),
          RaidMode.NORMAL, RaidDifficulty.NORMAL, 1, tierId));

      assertEquals(2, games.all().size());
      assertEquals(null, games.created().getFirst().tierId());
      assertEquals(tierId, games.created().getLast().tierId());
    }

    @Test
    @DisplayName("a party that cannot fit in an existing room triggers a fresh room")
    void provisionsForWholePartyCapacity() {
      InstanceProvisioner provisioner = provisioner(1, 4);
      provisioner.start();
      FakeCraftovGame nearlyFull = (FakeCraftovGame) games.create(
          CraftovMapId.of("debug_zone"), RaidMode.CONFIDENTIAL, RaidDifficulty.HARD)
          .value().orElseThrow();
      nearlyFull.setPlayerCount(11);

      provisioner.onProvisionRequest(new ProvisionRequest(
          ServerId.of(OWN_SERVER_ID), CraftovMapId.of("debug_zone"),
          RaidMode.CONFIDENTIAL, RaidDifficulty.HARD, 2));

      assertEquals(3, games.all().size());
      assertEquals(0, games.created().getLast().playerCount());
      assertEquals(RaidMode.CONFIDENTIAL, games.created().getLast().mode());
    }

    @Test
    @DisplayName("foreign serverId is ignored")
    void foreignServerIdIsIgnored() {
      InstanceProvisioner provisioner = provisioner(1, 4);
      provisioner.start();
      games.created().getFirst().setState(CraftovGameState.RUNNING);

      provisioner.onProvisionRequest(new ProvisionRequest(
          ServerId.of("game-02"), CraftovMapId.of("debug_zone")));

      assertEquals(1, games.all().size());
    }
  }

  @Nested
  @DisplayName("idle pool retirement")
  class IdlePoolRetirement {

    @Test
    @DisplayName("retires stale on-demand rooms while retaining the default ready floor")
    void retiresOnDemandButKeepsDefaultFloor() {
      InstanceProvisioner provisioner = provisioner(1, 4);
      provisioner.start();
      provisioner.onProvisionRequest(new ProvisionRequest(
          ServerId.of(OWN_SERVER_ID), CraftovMapId.of("debug_zone"),
          RaidMode.CONFIDENTIAL, RaidDifficulty.HARD));
      assertEquals(2, games.all().size());

      timeSource.advance(Duration.ofSeconds(121));
      scheduler.advanceBy(PASS_INTERVAL);

      assertEquals(1, games.all().size());
      assertEquals(RaidMode.NORMAL, games.created().getFirst().mode());
      assertEquals(RaidDifficulty.NORMAL, games.created().getFirst().difficulty());
    }
  }

  @Nested
  @DisplayName("stop")
  class Stop {

    @Test
    @DisplayName("cancels the timer")
    void cancelsTimer() {
      InstanceProvisioner provisioner = provisioner(1, 4);
      provisioner.start();
      provisioner.stop();
      games.created().getFirst().setState(CraftovGameState.RUNNING);

      scheduler.advanceBy(PASS_INTERVAL);

      assertEquals(1, games.all().size());
      assertEquals(0, scheduler.pendingCount());
    }

    @Test
    @DisplayName("is safe before start and when repeated")
    void isSafeBeforeStartAndWhenRepeated() {
      InstanceProvisioner provisioner = provisioner(1, 4);
      provisioner.stop();
      provisioner.start();
      provisioner.stop();
      provisioner.stop();

      assertEquals(0, scheduler.pendingCount());
    }
  }
}
