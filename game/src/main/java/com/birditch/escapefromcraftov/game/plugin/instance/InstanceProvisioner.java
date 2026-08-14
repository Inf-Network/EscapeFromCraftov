package com.birditch.escapefromcraftov.game.plugin.instance;

import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.api.network.ProvisionRequest;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
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
  private static final Duration EMPTY_WAITING_TTL = Duration.ofSeconds(120);

  private final ProvisionerDeps deps;
  private @Nullable ScheduledTask timer;

  public InstanceProvisioner(ProvisionerDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
  }

  public void start() {
    Preconditions.checkState(timer == null, "InstanceProvisioner already started");
    maintenancePass();
    timer = deps.scheduler().runTimer(PASS_INTERVAL, PASS_INTERVAL, this::maintenancePass);
  }

  /// Handles a lobby provision hint whose payload is the target server id;
  /// hints addressed to other servers are ignored. Callers must already have
  /// hopped to the main thread.
  public void onProvisionRequest(ProvisionRequest request) {
    Preconditions.checkNotNull(request, "request");
    if (deps.config().serverId().equals(request.serverId())) {
      ensureAvailable(request.mapId(), request.mode(), request.difficulty(),
          request.tierId(), request.requiredSlots(), 1);
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
    ensureAvailable(deps.config().defaultMap(), RaidMode.NORMAL, RaidDifficulty.NORMAL,
        null, 1, deps.config().minReadyInstances());
  }

  private void maintenancePass() {
    retireExpiredEmptyWaitingRooms();
    provisionDefaultCapacity();
  }

  /// On-demand pools must not consume the finite instance cap forever. A long
  /// grace period keeps Lobby reservations/slow proxy transfers safe; the
  /// default pool always retains its configured ready floor.
  private void retireExpiredEmptyWaitingRooms() {
    var cutoff = deps.timeSource().now().minus(EMPTY_WAITING_TTL);
    CraftovMapId defaultMap = deps.config().defaultMap();
    long defaultReady = availableWaitingCount(
        defaultMap, RaidMode.NORMAL, RaidDifficulty.NORMAL, null, 1);
    for (CraftovGame game : List.copyOf(deps.games().all())) {
      if (game.state() != CraftovGameState.WAITING || game.playerCount() != 0
          || game.createdAt().isAfter(cutoff)) {
        continue;
      }
      boolean defaultPool = game.mapId().equals(defaultMap)
          && game.mode() == RaidMode.NORMAL
          && game.difficulty() == RaidDifficulty.NORMAL
          && game.tierId() == null;
      if (defaultPool && defaultReady <= deps.config().minReadyInstances()) {
        continue;
      }
      Result<CraftovGame> stopped = deps.games().stop(game.id());
      if (stopped instanceof Result.Failure<CraftovGame>(String message)) {
        deps.logger().warning("Failed to retire stale WAITING instance "
            + game.id().value() + ": " + message);
      } else if (defaultPool) {
        defaultReady--;
      }
    }
  }

  private void ensureAvailable(CraftovMapId mapId, RaidMode mode,
      RaidDifficulty difficulty, @Nullable RaidTierId tierId, int requiredSlots, int target) {
    if (!deps.maps().isEnabled(mapId.value())) {
      return;
    }
    if (requiredSlots > deps.coreSettings().maxPlayers()) {
      deps.logger().warning("Instance provisioning cannot fit a party of " + requiredSlots
          + " into max-players=" + deps.coreSettings().maxPlayers());
      return;
    }
    while (availableWaitingCount(mapId, mode, difficulty, tierId, requiredSlots) < target
        && deps.games().all().size() < deps.coreSettings().maxInstances()) {
      if (deps.games().create(mapId, mode, difficulty, tierId)
          instanceof Result.Failure<CraftovGame>(String message)) {
        deps.logger().warning("Instance provisioning halted: " + message);
        return;
      }
    }
  }

  private long availableWaitingCount(CraftovMapId mapId, RaidMode mode,
      RaidDifficulty difficulty, @Nullable RaidTierId tierId, int requiredSlots) {
    return deps.games().all().stream()
        .filter(game -> game.mapId().equals(mapId))
        .filter(game -> game.mode() == mode && game.difficulty() == difficulty)
        .filter(game -> Objects.equals(game.tierId(), tierId))
        .filter(game -> game.state() == CraftovGameState.WAITING)
        .filter(game -> game.playerCount() + requiredSlots <= deps.coreSettings().maxPlayers())
        .count();
  }
}
