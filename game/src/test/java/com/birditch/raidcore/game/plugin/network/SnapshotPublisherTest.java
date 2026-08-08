package com.birditch.raidcore.game.plugin.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.RaidException;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.network.GameServerSnapshot;
import com.birditch.raidcore.api.network.InstanceDirectory;
import com.birditch.raidcore.api.network.InstanceSnapshot;
import com.birditch.raidcore.api.network.ServerId;
import com.birditch.raidcore.api.testing.ManualScheduler;
import com.birditch.raidcore.api.testing.MutableTimeSource;
import com.birditch.raidcore.core.config.RaidCoreConfig;
import com.birditch.raidcore.game.plugin.GameServerConfig;
import com.birditch.raidcore.game.plugin.instance.FakeGameService;
import com.birditch.raidcore.game.plugin.instance.FakeRaidGame;
import com.birditch.raidcore.game.plugin.instance.InstanceWorldIndex;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SnapshotPublisher")
class SnapshotPublisherTest {

  private static final Duration INTERVAL = Duration.ofSeconds(2);
  private static final RaidMapId MAP = RaidMapId.of("debug_zone");

  private FakeGameService games;
  private InstanceWorldIndex index;
  private CapturingDirectory directory;
  private ManualScheduler scheduler;
  private MutableTimeSource timeSource;
  private Logger logger;
  private CapturingHandler logHandler;
  private SnapshotPublisher publisher;

  @BeforeEach
  void setUp() {
    games = new FakeGameService();
    index = new InstanceWorldIndex();
    directory = new CapturingDirectory();
    scheduler = new ManualScheduler();
    timeSource = new MutableTimeSource(0L);
    logger = Logger.getLogger(SnapshotPublisherTest.class.getName());
    logger.setUseParentHandlers(false);
    logHandler = new CapturingHandler();
    logger.addHandler(logHandler);
    publisher = new SnapshotPublisher(new PublisherDeps(
        games,
        index,
        directory,
        new GameServerConfig(ServerId.of("game-01"), "raid-01", 1, "maps", true, "lobby",
            (int) INTERVAL.toSeconds()),
        new RaidCoreConfig(
            false,
            new RaidCoreConfig.GameSettings(12, 3, 1800, 4),
            new RaidCoreConfig.ExtractionSettings(10)),
        timeSource,
        scheduler,
        logger));
  }

  @AfterEach
  void tearDown() {
    logger.removeHandler(logHandler);
  }

  /// Advances the manual clock and the scheduler in lockstep, as real time
  /// would.
  private void tick(Duration d) {
    timeSource.advance(d);
    scheduler.advanceBy(d);
  }

  @Nested
  @DisplayName("heartbeat")
  class Heartbeat {

    @Test
    @DisplayName("first tick publishes a snapshot with ttl of three intervals")
    void firstTickPublishesSnapshot() {
      FakeRaidGame ready = games.addGame(MAP);
      ready.setPlayerCount(3);
      FakeRaidGame notReady = games.addGame(MAP);
      index.register(ready.id(), "raid_" + ready.id().value());
      index.markReady(ready.id());
      index.register(notReady.id(), "raid_" + notReady.id().value());
      timeSource.set(1234L);

      publisher.start();
      scheduler.advanceBy(Duration.ZERO);

      assertEquals(1, directory.publications().size());
      CapturingDirectory.Publication publication = directory.publications().getFirst();
      assertEquals(INTERVAL.multipliedBy(3), publication.ttl());
      GameServerSnapshot snapshot = publication.snapshot();
      assertEquals(ServerId.of("game-01"), snapshot.serverId());
      assertEquals("raid-01", snapshot.proxyName());
      assertEquals(1234L, snapshot.updatedAtMillis());
      assertEquals(4, snapshot.maxInstances());
      assertEquals(2, snapshot.instances().size());
      InstanceSnapshot first = snapshot.instances().get(0);
      assertEquals(ready.id(), first.gameId());
      assertEquals(MAP, first.mapId());
      assertEquals(3, first.playerCount());
      assertEquals(12, first.maxPlayers());
      assertTrue(first.worldReady());
      InstanceSnapshot second = snapshot.instances().get(1);
      assertEquals(notReady.id(), second.gameId());
      assertFalse(second.worldReady());
    }

    @Test
    @DisplayName("keeps publishing on every interval")
    void keepsPublishingOnEveryInterval() {
      publisher.start();
      scheduler.advanceBy(Duration.ZERO);
      tick(INTERVAL);
      tick(INTERVAL);

      assertEquals(3, directory.publications().size());
      assertTrue(directory.publications().stream()
          .allMatch(publication -> publication.ttl().equals(INTERVAL.multipliedBy(3))));
    }

    @Test
    @DisplayName("rejects a second start")
    void rejectsSecondStart() {
      publisher.start();

      assertThrows(IllegalStateException.class, publisher::start);
    }
  }

  @Nested
  @DisplayName("publish failure")
  class PublishFailure {

    @Test
    @DisplayName("does not break subsequent ticks and throttles the warning")
    void doesNotBreakTicksAndThrottlesWarning() {
      directory.failWith("Redis 不可用");
      publisher.start();
      scheduler.advanceBy(Duration.ZERO);

      assertEquals(1, logHandler.warningCount());
      assertTrue(logHandler.lastMessage().contains("向实例目录发布服务器快照失败"));

      for (int i = 0; i < 14; i++) {
        tick(INTERVAL);
      }
      assertEquals(15, directory.publications().size());
      assertEquals(1, logHandler.warningCount());

      tick(INTERVAL);
      assertEquals(16, directory.publications().size());
      assertEquals(2, logHandler.warningCount());

      directory.publishNormally();
      tick(INTERVAL);
      assertEquals(17, directory.publications().size());
      assertEquals(2, logHandler.warningCount());
    }
  }

  @Nested
  @DisplayName("stop")
  class Stop {

    @Test
    @DisplayName("cancels the timer and publishes a fast-expire snapshot")
    void cancelsTimerAndPublishesFastExpireSnapshot() {
      publisher.start();
      scheduler.advanceBy(Duration.ZERO);

      publisher.stop();

      assertEquals(2, directory.publications().size());
      assertEquals(Duration.ofMillis(1), directory.publications().getLast().ttl());
      assertEquals(0, scheduler.pendingCount());

      tick(INTERVAL);
      assertEquals(2, directory.publications().size());
    }

    @Test
    @DisplayName("still publishes best-effort when the directory fails")
    void stillPublishesBestEffortWhenDirectoryFails() {
      publisher.start();
      directory.failWith("Redis 不可用");

      publisher.stop();

      assertEquals(1, directory.publications().size());
      assertEquals(Duration.ofMillis(1), directory.publications().getLast().ttl());
      assertEquals(1, logHandler.warningCount());
    }
  }

  /// Records every publish attempt (snapshot and ttl) and optionally fails
  /// the returned future to simulate a broken Redis.
  private static final class CapturingDirectory implements InstanceDirectory {

    record Publication(GameServerSnapshot snapshot, Duration ttl) {}

    private final List<Publication> publications = new ArrayList<>();
    private @Nullable String failWith;

    void failWith(String message) {
      this.failWith = message;
    }

    void publishNormally() {
      this.failWith = null;
    }

    List<Publication> publications() {
      return List.copyOf(publications);
    }

    @Override
    public CompletableFuture<Void> publish(GameServerSnapshot snapshot, Duration ttl) {
      publications.add(new Publication(snapshot, ttl));
      if (failWith != null) {
        return CompletableFuture.failedFuture(new RaidException(failWith));
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<GameServerSnapshot>> activeServers() {
      throw new UnsupportedOperationException(
          "activeServers is not supported by CapturingDirectory");
    }
  }

  private static final class CapturingHandler extends Handler {

    private final List<LogRecord> records = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    long warningCount() {
      return records.stream().filter(r -> r.getLevel() == Level.WARNING).count();
    }

    String lastMessage() {
      return records.getLast().getMessage();
    }
  }
}
