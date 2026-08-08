package com.birditch.raidcore.game.plugin.network;

import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.network.MatchAssignment;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.result.Result;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/// Consumes the lobby-issued pending match assignment when a player joins this
/// server and routes them into the assigned raid instance.
///
/// The atomic consume (GETDEL) runs async on the store's IO executor; applying
/// the outcome hops back to the main thread via `RaidScheduler.runSync`. A
/// Redis failure logs a WARNING and leaves the player in the lobby world — a
/// broken assignment must never break the join itself.
public final class PendingJoinHandler {

  private static final String GAME_FULL_ERROR = "Game is full";

  private final PendingDeps deps;

  public PendingJoinHandler(PendingDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
  }

  /// Called by the connection listener after the player's session is created.
  /// No pending assignment means the player simply stays in the default world.
  public void handle(Player player) {
    Preconditions.checkNotNull(player, "player");
    deps.store().consumePending(player.getUniqueId()).whenComplete((assignment, error) -> {
      if (error != null) {
        logConsumeFailure(player, error);
        return;
      }
      assignment.ifPresent(match -> deps.scheduler().runSync(() -> apply(player, match)));
    });
  }

  private void apply(Player player, MatchAssignment assignment) {
    if (!player.isOnline()) {
      return;
    }
    if (!assignment.serverId().equals(deps.config().serverId())) {
      deps.logger().warning("Ignoring pending assignment for " + player.getName()
          + ": it targets server " + assignment.serverId().value()
          + ", this server is " + deps.config().serverId().value());
      return;
    }
    switch (deps.games().join(assignment.gameId(), player.getUniqueId())) {
      case Result.Success<RaidGame> _ -> player.sendMessage(deps.messages().raw(
          "game.sent-to-instance",
          Placeholder.unparsed("id", assignment.gameId().value()),
          Placeholder.unparsed("map", assignment.mapId().value())));
      case Result.Failure<RaidGame>(String message) -> rejectAssignment(player, message);
    }
  }

  private void rejectAssignment(Player player, String reason) {
    String key = GAME_FULL_ERROR.equals(reason) ? "game.instance-full" : "game.assignment-invalid";
    player.sendMessage(deps.messages().raw(key, Placeholder.unparsed("reason", reason)));
    returnToLobby(player);
  }

  private void returnToLobby(Player player) {
    if (deps.config().proxyEnabled()) {
      deps.bungee().send(player, deps.config().lobbyServerName());
      return;
    }
    player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
  }

  /// May run on the store's IO thread; `java.util.logging` is thread-safe.
  private void logConsumeFailure(Player player, Throwable error) {
    Throwable cause = error instanceof CompletionException && error.getCause() != null
        ? error.getCause()
        : error;
    deps.logger().warning("Failed to consume pending assignment for " + player.getName()
        + ": " + cause);
  }
}
