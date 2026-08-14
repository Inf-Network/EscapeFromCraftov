package com.birditch.escapefromcraftov.lobby.matchmaking;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.network.GameServerSnapshot;
import com.birditch.escapefromcraftov.api.network.InstanceSnapshot;
import com.birditch.escapefromcraftov.api.network.ServerId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// Pure selection of the craftov instance the next queued player should join.
///
/// An instance is eligible when it is `WAITING`, its world is ready and its
/// player count plus outstanding reservations is below its cap. Among eligible
/// instances the fullest one wins (fill instances instead of spreading
/// players); equal loads tiebreak on the lexicographically lowest game id.
public final class InstancePicker {

  private InstancePicker() {}

  public static Optional<Candidate> pick(
      List<GameServerSnapshot> servers,
      CraftovMapId requestedMap,
      Map<String, Integer> reservedByInstance) {
    return pick(servers, requestedMap, RaidMode.NORMAL, RaidDifficulty.NORMAL, 1,
        reservedByInstance);
  }

  /// Selects one instance for a complete queue group. `requiredSlots` is
  /// checked as a unit, so a three-player party is never partially placed.
  public static Optional<Candidate> pick(
      List<GameServerSnapshot> servers,
      CraftovMapId requestedMap,
      RaidMode requestedMode,
      RaidDifficulty requestedDifficulty,
      int requiredSlots,
      Map<String, Integer> reservedByInstance) {
    Preconditions.checkNotNull(servers, "servers");
    Preconditions.checkNotNull(requestedMap, "requestedMap");
    Preconditions.checkNotNull(requestedMode, "requestedMode");
    Preconditions.checkNotNull(requestedDifficulty, "requestedDifficulty");
    Preconditions.checkArgument(requiredSlots >= 1, "requiredSlots must be at least 1");
    Preconditions.checkNotNull(reservedByInstance, "reservedByInstance");
    Candidate best = null;
    int bestLoad = -1;
    for (GameServerSnapshot server : servers) {
      for (InstanceSnapshot instance : server.instances()) {
        if (!instance.mapId().equals(requestedMap)
            || instance.mode() != requestedMode
            || instance.difficulty() != requestedDifficulty
            || instance.state() != CraftovGameState.WAITING
            || !instance.worldReady()) {
          continue;
        }
        int load = instance.playerCount() + reservedByInstance.getOrDefault(
            reservationKey(server.serverId(), instance.gameId()), 0);
        if (load + requiredSlots > instance.maxPlayers()) {
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
  public static String reservationKey(ServerId serverId, CraftovGameId gameId) {
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
