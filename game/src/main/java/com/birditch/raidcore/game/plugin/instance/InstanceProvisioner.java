package com.birditch.raidcore.game.plugin.instance;

import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.scheduler.ScheduledTask;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.result.Result;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/// Self-provisioning: keeps at least `min-ready-instances` WAITING raid
/// instances available (never exceeding the `max-instances` cap) via an
/// immediate pass on [#start] plus a 5-second main-thread timer. The lobby
/// never creates instances; it may nudge this server over Redis pub/sub,
/// which lands in [#onProvisionHint] after the caller hops to the main
/// thread.
///
/// MAIN-THREAD only, like the [com.birditch.raidcore.api.game.RaidGameService]
/// it drives.
public final class InstanceProvisioner {

  private static final RaidMapId DEFAULT_MAP = RaidMapId.of("debug_zone");
  private static final Duration PASS_INTERVAL = Duration.ofSeconds(5);

  private final ProvisionerDeps deps;
  private @Nullable ScheduledTask timer;

  public InstanceProvisioner(ProvisionerDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
  }

  public void start() {
    Preconditions.checkState(timer == null, "InstanceProvisioner already started");
    provisionPass();
    timer = deps.scheduler().runTimer(PASS_INTERVAL, PASS_INTERVAL, this::provisionPass);
  }

  /// Handles a lobby provision hint whose payload is the target server id;
  /// hints addressed to other servers are ignored. Callers must already have
  /// hopped to the main thread.
  public void onProvisionHint(String serverIdPayload) {
    Preconditions.checkNotNull(serverIdPayload, "serverIdPayload");
    if (deps.config().serverId().value().equals(serverIdPayload)) {
      provisionPass();
    }
  }

  /// Cancels the timer; safe to call repeatedly or before [#start].
  public void stop() {
    if (timer != null) {
      timer.cancel();
      timer = null;
    }
  }

  private void provisionPass() {
    while (waitingCount() < deps.config().minReadyInstances()
        && deps.games().all().size() < deps.coreSettings().maxInstances()) {
      if (deps.games().create(DEFAULT_MAP) instanceof Result.Failure<RaidGame>(String message)) {
        deps.logger().warning("Instance provisioning halted: " + message);
        return;
      }
    }
  }

  private long waitingCount() {
    return deps.games().all().stream()
        .filter(game -> game.state() == RaidGameState.WAITING)
        .count();
  }
}
