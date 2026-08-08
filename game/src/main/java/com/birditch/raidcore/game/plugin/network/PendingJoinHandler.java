package com.birditch.raidcore.game.plugin.network;

import com.birditch.raidcore.api.game.GameFailures;
import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.network.MatchAssignment;
import com.birditch.raidcore.bukkit.MapNames;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.result.Result;
import com.birditch.raidcore.game.plugin.LobbyReturn;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

/// Consumes the lobby-issued pending match assignment when a player joins this
/// server and routes them into the assigned raid instance.
///
/// The atomic consume (GETDEL) runs async on the store's IO executor; applying
/// the outcome hops back to the main thread via `RaidScheduler.runSync`. A
/// Redis failure logs a WARNING and leaves the player in the lobby world — a
/// broken assignment must never break the join itself.
public final class PendingJoinHandler {

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
      deps.logger().warning("忽略发给 " + player.getName() + " 的匹配分配:目标服务器是 "
          + assignment.serverId().value() + ",本服是 " + deps.config().serverId().value());
      return;
    }
    switch (deps.games().join(assignment.gameId(), player.getUniqueId())) {
      case Result.Success<RaidGame> _ -> player.sendMessage(deps.messages().raw(
          "game.sent-to-instance",
          Placeholder.unparsed("id", assignment.gameId().value()),
          Placeholder.component("map", MapNames.display(deps.messages(), assignment.mapId()))));
      case Result.Failure<RaidGame>(String message) -> rejectAssignment(player, message);
    }
  }

  private void rejectAssignment(Player player, String reason) {
    String key = GameFailures.GAME_FULL.equals(reason)
        ? "game.instance-full"
        : "game.assignment-invalid";
    player.sendMessage(deps.messages().raw(key, Placeholder.unparsed("reason", reason)));
    LobbyReturn.send(deps.bungee(), deps.config(), deps.messages(), player);
  }

  /// May run on the store's IO thread; `java.util.logging` is thread-safe.
  private void logConsumeFailure(Player player, Throwable error) {
    Throwable cause = error instanceof CompletionException && error.getCause() != null
        ? error.getCause()
        : error;
    deps.logger().warning("消费 " + player.getName() + " 的匹配分配失败:" + cause);
  }
}
