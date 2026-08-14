package com.birditch.escapefromcraftov.game.plugin.instance;

import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.network.ProvisionRequest;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/// Self-provisioning: keeps at least `min-ready-instances` WAITING craftov
/// instances available (never exceeding the `max-instances` cap) via an
/// immediate pass on [#start] plus a 5-second main-thread timer. The lobby
/// never creates instances; it may nudge this server over Redis pub/sub,
/// which lands in [#onProvisionHint] after the caller hops to the main
/// thread.
///
/// MAIN-THREAD only, like the [com.birditch.escapefromcraftov.api.game.CraftovGameService]
/// it drives.
public final class InstanceProvisioner {

  private static final Duration PASS_INTERVAL = Duration.ofSeconds(5);

  private final ProvisionerDeps deps;
  private @Nullable ScheduledTask timer;

  public InstanceProvisioner(ProvisionerDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
  }

  public void start() {
    Preconditions.checkState(timer == null, "InstanceProvisioner already started");
    provisionDefaultCapacity();
    timer = deps.scheduler().runTimer(PASS_INTERVAL, PASS_INTERVAL, this::provisionDefaultCapacity);
  }

  /// Handles a lobby provision hint whose payload is the target server id;
  /// hints addressed to other servers are ignored. Callers must already have
  /// hopped to the main thread.
  public void onProvisionRequest(ProvisionRequest request) {
    Preconditions.checkNotNull(request, "request");
    if (deps.config().serverId().equals(request.serverId())) {
      ensureAvailable(request.mapId(), 1);
    }
  }

  /// Cancels the timer; safe to call repeatedly or before [#start].
  public void stop() {
    if (timer != null) {
      timer.cancel();
      timer = null;
    }
  }

  private void provisionDefaultCapacity() {
    ensureAvailable(deps.config().defaultMap(), deps.config().minReadyInstances());
  }

  private void ensureAvailable(CraftovMapId mapId, int target) {
    if (!deps.maps().isEnabled(mapId.value())) {
      return;
    }
    while (availableWaitingCount(mapId) < target
        && deps.games().all().size() < deps.coreSettings().maxInstances()) {
      if (deps.games().create(mapId) instanceof Result.Failure<CraftovGame>(String message)) {
        deps.logger().warning("Instance provisioning halted: " + message);
        return;
      }
    }
  }

  private long availableWaitingCount(CraftovMapId mapId) {
    return deps.games().all().stream()
        .filter(game -> game.mapId().equals(mapId))
        .filter(game -> game.state() == CraftovGameState.WAITING)
        .filter(game -> game.playerCount() < deps.coreSettings().maxPlayers())
        .count();
  }
}
