package com.birditch.escapefromcraftov.game.plugin.start;

import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovStartedEvent;
import com.birditch.escapefromcraftov.api.event.game.PlayerJoinedCraftovEvent;
import com.birditch.escapefromcraftov.api.event.game.PlayerLeftCraftovEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.bukkit.BungeeConnector;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.game.plugin.GameServerConfig;
import com.birditch.escapefromcraftov.game.plugin.LobbyReturn;
import com.birditch.escapefromcraftov.game.plugin.network.PendingJoinHandler;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Prevents a partial Velocity transfer from freezing the members that did
 * reach a WAITING game forever.
 *
 * <p>The timer starts with the first room member and is cancelled as soon as
 * the configured minimum arrives or the game starts/ends. Expiry is deliberately
 * a pre-start cancellation: retained assignments are released before leaving,
 * so no LOADOUT row is claimed and no terminal raid result is produced.</p>
 */
public final class WaitingArrivalTimeout implements AutoCloseable {

  private final CraftovGameService games;
  private final CraftovScheduler scheduler;
  private final PendingJoinHandler pendingJoins;
  private final BungeeConnector bungee;
  private final GameServerConfig config;
  private final Messages messages;
  private final Logger logger;
  private final int minimumPlayers;
  private final Duration timeout;
  private final Map<CraftovGameId, ScheduledTask> tasks = new HashMap<>();

  public WaitingArrivalTimeout(CraftovGameService games, CraftovScheduler scheduler,
      PendingJoinHandler pendingJoins, BungeeConnector bungee, GameServerConfig config,
      Messages messages, Logger logger) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.pendingJoins = Preconditions.checkNotNull(pendingJoins, "pendingJoins");
    this.bungee = Preconditions.checkNotNull(bungee, "bungee");
    this.config = Preconditions.checkNotNull(config, "config");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    this.logger = Preconditions.checkNotNull(logger, "logger");
    Preconditions.checkArgument(config.minimumPlayers() > 0,
        "minimumPlayers must be positive");
    Preconditions.checkArgument(config.waitingArrivalTimeoutSeconds() > 0,
        "waitingArrivalTimeoutSeconds must be positive");
    this.minimumPlayers = config.minimumPlayers();
    this.timeout = Duration.ofSeconds(config.waitingArrivalTimeoutSeconds());
  }

  @Subscribe
  public void onPlayerJoined(PlayerJoinedCraftovEvent event) {
    reconcile(event.gameId());
  }

  @Subscribe
  public void onPlayerLeft(PlayerLeftCraftovEvent event) {
    reconcile(event.gameId());
  }

  @Subscribe
  public void onCraftovStarted(CraftovStartedEvent event) {
    cancel(event.gameId());
  }

  @Subscribe
  public void onCraftovEnded(CraftovEndedEvent event) {
    cancel(event.gameId());
  }

  @Override
  public void close() {
    tasks.values().forEach(ScheduledTask::cancel);
    tasks.clear();
  }

  private void reconcile(CraftovGameId gameId) {
    CraftovGame game = games.find(gameId).orElse(null);
    if (game == null) {
      cancel(gameId);
      return;
    }
    switch (decision(game.state(), game.playerCount(), minimumPlayers)) {
      case WAIT -> tasks.computeIfAbsent(gameId,
          id -> scheduler.runLater(timeout, () -> expire(id)));
      case READY, INACTIVE -> cancel(gameId);
      case RECYCLE_EMPTY -> {
        cancel(gameId);
        recycleEmpty(gameId);
      }
    }
  }

  private void expire(CraftovGameId gameId) {
    tasks.remove(gameId);
    CraftovGame game = games.find(gameId).orElse(null);
    if (game == null
        || decision(game.state(), game.playerCount(), minimumPlayers) != Decision.WAIT) {
      return;
    }
    List<UUID> members = game.players().stream().map(player -> player.playerId()).toList();
    logger.warning("匹配成员到齐超时，正在无损取消房间 " + gameId.value()
        + " (" + game.playerCount() + "/" + minimumPlayers + ")");
    for (UUID playerId : members) {
      Player online = Bukkit.getPlayer(playerId);
      // Release the retained old assignment before PlayerLeft dispatch can
      // synchronously begin the profile-save and Lobby return flow.
      pendingJoins.cancelBeforeStart(playerId, gameId);
      Result<CraftovGame> cancelled = games.cancelWaitingMember(gameId, playerId);
      if (cancelled.isSuccess()) {
        if (online != null && online.isOnline()) {
          online.sendMessage(messages.raw("game.waiting-arrival-timeout"));
        }
        // InstanceWorldBridge handles the profile-save + LobbyReturn flow for
        // every successful PlayerLeftCraftovEvent.
        continue;
      }
      logger.severe("到齐超时后无法安全取消 WAITING 成员 " + playerId + ":"
          + cancelled.error().orElse("unknown cancellation failure"));
      CraftovGame current = games.find(gameId).orElse(null);
      boolean stillMember = current != null && current.players().stream()
          .anyMatch(player -> player.playerId().equals(playerId));
      if (!stillMember && online != null && online.isOnline()) {
        online.sendMessage(messages.raw("game.waiting-arrival-timeout"));
        online.setInvulnerable(false);
        LobbyReturn.send(bungee, config, messages, online);
      }
    }
    CraftovGame remaining = games.find(gameId).orElse(null);
    if (remaining != null && remaining.state() == CraftovGameState.WAITING
        && remaining.playerCount() == 0) {
      recycleEmpty(gameId);
    } else if (remaining != null && remaining.state() == CraftovGameState.WAITING) {
      // The explicit manager operation cannot fail for the immutable member
      // snapshot under normal main-thread execution. Do not create an
      // unbounded retry loop if an alternate implementation violates that
      // contract; retain the room for operator inspection instead.
      logger.severe("到齐超时取消后房间仍有 " + remaining.playerCount()
          + " 名成员，已停止自动重试:" + gameId.value());
    }
  }

  private void recycleEmpty(CraftovGameId gameId) {
    games.find(gameId).filter(game -> game.state() == CraftovGameState.WAITING)
        .filter(game -> game.playerCount() == 0)
        .ifPresent(_ -> games.stop(gameId).error().ifPresent(message ->
            logger.warning("无法回收空的等待房间 " + gameId.value() + ":" + message)));
  }

  private void cancel(CraftovGameId gameId) {
    ScheduledTask task = tasks.remove(gameId);
    if (task != null) task.cancel();
  }

  static Decision decision(CraftovGameState state, int playerCount, int minimumPlayers) {
    Preconditions.checkNotNull(state, "state");
    Preconditions.checkArgument(playerCount >= 0, "playerCount must not be negative");
    Preconditions.checkArgument(minimumPlayers > 0, "minimumPlayers must be positive");
    if (state != CraftovGameState.WAITING) return Decision.INACTIVE;
    if (playerCount == 0) return Decision.RECYCLE_EMPTY;
    if (playerCount < minimumPlayers) return Decision.WAIT;
    return Decision.READY;
  }

  enum Decision {
    WAIT,
    READY,
    RECYCLE_EMPTY,
    INACTIVE
  }
}
