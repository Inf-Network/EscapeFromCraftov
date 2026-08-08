package com.birditch.raidcore.game.plugin.network;

import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.network.GameServerSnapshot;
import com.birditch.raidcore.api.network.InstanceSnapshot;
import com.birditch.raidcore.api.scheduler.ScheduledTask;
import com.birditch.raidcore.common.check.Preconditions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;

/// Publishes this server's [GameServerSnapshot] heartbeat to the instance
/// directory on a fixed interval.
///
/// Main-thread confined: the timer body reads main-confined game state; the
/// Redis write itself runs async inside the directory implementation. Each
/// snapshot carries a TTL of three intervals, so a crashed or partitioned
/// server expires out of the directory after missing two heartbeats. A failed
/// publish logs a single WARNING (no SEVERE spam while Redis is down) and
/// never breaks the timer.
public final class SnapshotPublisher {

  private static final Duration FAST_EXPIRE_TTL = Duration.ofMillis(1);

  private final PublisherDeps deps;
  private final Duration interval;
  private final Duration heartbeatTtl;
  private @Nullable ScheduledTask task;

  public SnapshotPublisher(PublisherDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
    this.interval = Duration.ofSeconds(deps.config().snapshotIntervalSeconds());
    this.heartbeatTtl = interval.multipliedBy(3);
  }

  /// Starts the heartbeat timer; the first publish fires on the next tick so
  /// the server becomes visible in the directory right after boot.
  public void start() {
    Preconditions.checkState(task == null, "SnapshotPublisher is already started");
    task = deps.scheduler().runTimer(Duration.ZERO, interval, this::publishHeartbeat);
  }

  /// Cancels the timer and best-effort re-publishes the snapshot with a 1 ms
  /// TTL so the server drops out of the directory immediately instead of
  /// lingering until the heartbeat TTL runs out.
  public void stop() {
    if (task != null) {
      task.cancel();
      task = null;
    }
    publish(FAST_EXPIRE_TTL);
  }

  private void publishHeartbeat() {
    publish(heartbeatTtl);
  }

  private void publish(Duration ttl) {
    try {
      deps.directory().publish(buildSnapshot(), ttl).whenComplete((_, error) -> {
        if (error != null) {
          logPublishFailure(error);
        }
      });
    } catch (RuntimeException e) {
      logPublishFailure(e);
    }
  }

  private GameServerSnapshot buildSnapshot() {
    List<InstanceSnapshot> instances =
        deps.games().all().stream().map(this::toInstanceSnapshot).toList();
    return new GameServerSnapshot(deps.config().serverId(), deps.config().proxyName(),
        deps.timeSource().currentMillis(), deps.coreConfig().game().maxInstances(), instances);
  }

  private InstanceSnapshot toInstanceSnapshot(RaidGame game) {
    return new InstanceSnapshot(game.id(), game.mapId(), game.state(), game.playerCount(),
        deps.coreConfig().game().maxPlayers(), deps.index().isReady(game.id()));
  }

  private void logPublishFailure(Throwable error) {
    Throwable cause = error instanceof CompletionException && error.getCause() != null
        ? error.getCause()
        : error;
    deps.logger().warning("Failed to publish server snapshot to instance directory: " + cause);
  }
}
