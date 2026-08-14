package com.birditch.escapefromcraftov.game.plugin.network;

import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.network.GameServerSnapshot;
import com.birditch.escapefromcraftov.api.network.InstanceSnapshot;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/// Publishes this server's [GameServerSnapshot] heartbeat to the instance
/// directory on a fixed interval.
///
/// Main-thread confined: the timer body reads main-confined game state; the
/// Redis write itself runs async inside the directory implementation. Each
/// snapshot carries a TTL of three intervals, so a crashed or partitioned
/// server expires out of the directory after missing two heartbeats. A failed
/// publish logs a WARNING throttled to one per 30 seconds (no SEVERE spam
/// while Redis is down) and never breaks the timer; the failure callback runs
/// on IO threads, so the throttle state is an [AtomicLong] rather than the
/// main-thread-confined common Cooldown.
public final class SnapshotPublisher {

  private static final Duration FAST_EXPIRE_TTL = Duration.ofMillis(1);
  private static final long FAILURE_LOG_WINDOW_MILLIS = 30_000L;
  private static final long NEVER_LOGGED = Long.MIN_VALUE;

  private final PublisherDeps deps;
  private final Duration interval;
  private final Duration heartbeatTtl;
  private final AtomicLong lastFailureLogMillis = new AtomicLong(NEVER_LOGGED);
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

  private InstanceSnapshot toInstanceSnapshot(CraftovGame game) {
    return new InstanceSnapshot(game.id(), game.mapId(), game.state(), game.playerCount(),
        deps.coreConfig().game().maxPlayers(), deps.index().isReady(game.id()));
  }

  /// Runs on IO threads (the directory's async callback); a lock-free CAS on
  /// the last-log timestamp keeps a flapping Redis from flooding the log.
  private void logPublishFailure(Throwable error) {
    long now = deps.timeSource().currentMillis();
    long last = lastFailureLogMillis.get();
    if (last != NEVER_LOGGED && now - last < FAILURE_LOG_WINDOW_MILLIS) {
      return;
    }
    if (!lastFailureLogMillis.compareAndSet(last, now)) {
      return;
    }
    Throwable cause = error instanceof CompletionException && error.getCause() != null
        ? error.getCause()
        : error;
    deps.logger().warning("向实例目录发布服务器快照失败(30 秒内不再重复提示,恢复后自动继续): " + cause);
  }
}
