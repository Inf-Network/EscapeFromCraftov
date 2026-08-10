package com.birditch.raidcore.lobby.matchmaking;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.network.GameServerSnapshot;
import com.birditch.raidcore.api.network.InstanceSnapshot;
import com.birditch.raidcore.api.network.ServerId;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// Pure selection of the raid instance the next queued player should join.
///
/// An instance is eligible when it is `WAITING`, its world is ready and its
/// player count plus outstanding reservations is below its cap. Among eligible
/// instances the fullest one wins (fill instances instead of spreading
/// players); equal loads tiebreak on the lexicographically lowest game id.
public final class InstancePicker {

  private InstancePicker() {}

  public static Optional<Candidate> pick(
      List<GameServerSnapshot> servers,
      RaidMapId requestedMap,
      Map<String, Integer> reservedByInstance) {
    Preconditions.checkNotNull(servers, "servers");
    Preconditions.checkNotNull(requestedMap, "requestedMap");
    Preconditions.checkNotNull(reservedByInstance, "reservedByInstance");
    Candidate best = null;
    int bestLoad = -1;
    for (GameServerSnapshot server : servers) {
      for (InstanceSnapshot instance : server.instances()) {
        if (!instance.mapId().equals(requestedMap)
            || instance.state() != RaidGameState.WAITING
            || !instance.worldReady()) {
          continue;
        }
        int load = instance.playerCount() + reservedByInstance.getOrDefault(
            reservationKey(server.serverId(), instance.gameId()), 0);
        if (load >= instance.maxPlayers()) {
          continue;
        }
        if (beatsBest(best, bestLoad, instance, load)) {
          best = new Candidate(server, instance);
          bestLoad = load;
        }
      }
    }
    return Optional.ofNullable(best);
  }

  /// Key under which reservations for one instance are counted:
  /// `<serverId>:<gameId>`.
  public static String reservationKey(ServerId serverId, RaidGameId gameId) {
    Preconditions.checkNotNull(serverId, "serverId");
    Preconditions.checkNotNull(gameId, "gameId");
    return serverId.value() + ":" + gameId.value();
  }

  private static boolean beatsBest(
      @Nullable Candidate best, int bestLoad, InstanceSnapshot instance, int load) {
    if (best == null || load > bestLoad) {
      return true;
    }
    return load == bestLoad
        && instance.gameId().value().compareTo(best.instance().gameId().value()) < 0;
  }

  /// A joinable instance together with the server advertising it.
  public record Candidate(GameServerSnapshot server, InstanceSnapshot instance) {

    public Candidate {
      Preconditions.checkNotNull(server, "server");
      Preconditions.checkNotNull(instance, "instance");
    }
  }
}
