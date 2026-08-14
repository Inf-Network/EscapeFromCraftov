package com.birditch.escapefromcraftov.game.plugin.network;

import com.birditch.escapefromcraftov.api.game.GameFailures;
import com.birditch.escapefromcraftov.api.CraftovPermissions;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.network.MatchAssignment;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.inventory.CraftovInventoryClosedException;
import com.birditch.escapefromcraftov.api.inventory.CraftovInventoryOutcome;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.bukkit.MapNames;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.game.plugin.GameServerConfig;
import com.birditch.escapefromcraftov.game.plugin.LobbyReturn;
import java.util.concurrent.CompletionException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

/// Consumes the lobby-issued pending match assignment when a player joins this
/// server and routes them into the assigned craftov instance.
///
/// The atomic consume (GETDEL) runs async on the store's IO executor; every
/// outcome hops back to the main thread. This is a dedicated game server, so a
/// player is returned to Lobby unless one valid, ready assignment is consumed.
public final class PendingJoinHandler {

  private final PendingDeps deps;
  private final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();

  public PendingJoinHandler(PendingDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
  }

  /** Applies the fail-closed state before any profile or assignment IO starts. */
  public void prepare(Player player) {
    Preconditions.checkNotNull(player, "player");
    pendingPlayers.add(player.getUniqueId());
    if (!deps.menus().closeCurrent(player)) {
      deps.logger().warning("无法关闭 " + player.getName() + " 的 TrMenu 会话");
    }
    clearInventory(player);
    player.setInvulnerable(true);
  }

  public boolean isPending(UUID playerId) {
    return pendingPlayers.contains(Preconditions.checkNotNull(playerId, "playerId"));
  }

  public void release(UUID playerId) {
    pendingPlayers.remove(Preconditions.checkNotNull(playerId, "playerId"));
  }

  /// Called only after the player's PostgreSQL profile is ready.
  public void handle(Player player) {
    Preconditions.checkNotNull(player, "player");
    // Administrators enter the dedicated server as map editors. Do this before
    // touching Redis so maintenance work remains available when matchmaking is
    // unavailable or a pooled connection became stale after host sleep.
    if (player.hasPermission(CraftovPermissions.ADMIN)
        || player.hasPermission(CraftovPermissions.EDITOR)) {
      authorizeEditor(player);
      return;
    }
    deps.store().consumePending(player.getUniqueId()).whenComplete((assignment, error) -> {
      MatchAssignment match = assignment == null ? null : assignment.orElse(null);
      deps.scheduler().runSync(() -> completeConsume(player, match, error));
    });
  }

  public void profileFailed(Player player) {
    Preconditions.checkNotNull(player, "player");
    if (player.isOnline()) {
      player.sendMessage(deps.messages().raw("join.profile-failed"));
      LobbyReturn.send(deps.bungee(), deps.config(), deps.messages(), player);
    }
  }

  private void completeConsume(Player player, MatchAssignment assignment, Throwable error) {
    if (!player.isOnline()) {
      return;
    }
    if (error != null) {
      logConsumeFailure(player, error);
      rejectAssignment(player, "game.assignment-unavailable", "Redis unavailable");
      return;
    }
    if (assignment == null) {
      rejectAssignment(player, "game.assignment-missing", "missing assignment");
      return;
    }
    apply(player, assignment);
  }

  private void apply(Player player, MatchAssignment assignment) {
    if (!player.isOnline()) {
      return;
    }
    TargetValidation target = validateTarget(player.getUniqueId(), assignment, deps.config());
    if (target != TargetValidation.VALID) {
      deps.logger().warning("忽略发给 " + player.getName() + " 的匹配分配:" + switch (target) {
        case PLAYER_MISMATCH -> "分配中的玩家是 " + assignment.playerId();
        case SERVER_ID_MISMATCH -> "目标服务器 ID 是 " + assignment.serverId().value()
            + ",本服是 " + deps.config().serverId().value();
        case PROXY_NAME_MISMATCH -> "目标代理后端是 " + assignment.proxyName()
            + ",本服是 " + deps.config().proxyName();
        case VALID -> throw new IllegalStateException("VALID assignment reached rejection branch");
      });
      rejectAssignment(player, "game.assignment-invalid", target.playerMessage());
      return;
    }
    CraftovGame game = deps.games().find(assignment.gameId()).orElse(null);
    if (game == null) {
      rejectAssignment(player, "game.assignment-invalid", GameFailures.GAME_NOT_FOUND);
      return;
    }
    if (!game.mapId().equals(assignment.mapId())) {
      rejectAssignment(player, "game.assignment-invalid", "地图不匹配");
      return;
    }
    if (game.state() != CraftovGameState.WAITING || !deps.index().isReady(game.id())) {
      rejectAssignment(player, "game.assignment-invalid", "对局实例尚未就绪");
      return;
    }
    Result<CraftovGame> joined = deps.games().join(assignment.gameId(), player.getUniqueId());
    if (joined instanceof Result.Failure<CraftovGame>(String message)) {
      rejectAssignment(player,
          GameFailures.GAME_FULL.equals(message) ? "game.instance-full" : "game.assignment-invalid",
          message);
      return;
    }
    if (!deps.menus().closeCurrent(player)) {
      player.sendMessage(deps.messages().raw("game.assignment-unavailable",
          Placeholder.unparsed("reason", "菜单会话无法安全关闭")));
      leaveAndReturn(player);
      return;
    }
    player.sendMessage(deps.messages().raw("inventory.loadout-loading"));
    claimLoadout(player, assignment, 0);
  }

  private void claimLoadout(Player player, MatchAssignment assignment, int attempt) {
    if (!player.isOnline()) return;
    deps.inventories().claimLoadout(player.getUniqueId(), assignment.gameId())
        .whenComplete((items, error) -> deps.scheduler().runSync(() ->
            finishLoadout(player, assignment, items, error, attempt)));
  }

  private void finishLoadout(Player player, MatchAssignment assignment,
      List<StoredItem> items, Throwable error, int attempt) {
    if (error != null || items == null) {
      Throwable cause = unwrap(error);
      if (cause instanceof CraftovInventoryClosedException closed) {
        deps.logger().warning("出战装备领取已被既有结算关闭:" + player.getUniqueId()
            + "/" + closed.outcome());
        if (player.isOnline()) {
          player.sendMessage(deps.messages().raw("inventory.loadout-closed"));
          leaveAndReturn(player);
        }
        return;
      }
      deps.logger().log(Level.WARNING,
          "领取出战栏失败，将在 5 秒后自动重试 (第 " + (attempt + 1) + " 次):"
              + player.getUniqueId(), cause);
      if (!player.isOnline()) return;
      if (attempt == 0) {
        player.sendMessage(deps.messages().raw("inventory.loadout-retrying"));
      }
      deps.scheduler().runLater(Duration.ofSeconds(5),
          () -> claimLoadout(player, assignment, attempt + 1));
      return;
    }
    if (!player.isOnline()) {
      // PlayerQuitEvent owns the idempotent death settlement and room leave.
      return;
    }
    try {
      for (StoredItem item : items) {
        var overflow = player.getInventory().addItem(
            deps.itemStacks().fromStored(item).toArray(org.bukkit.inventory.ItemStack[]::new));
        if (!overflow.isEmpty()) {
          throw new IllegalStateException("出战物品超过玩家背包容量");
        }
      }
    } catch (RuntimeException mappingFailure) {
      clearInventory(player);
      List<ItemInstance> restore = items.stream().map(item ->
          new ItemInstance(UUID.randomUUID(), item.itemId(), item.quantity())).toList();
      restoreClaimedLoadout(player, assignment, restore, mappingFailure, 0);
      return;
    }
    release(player.getUniqueId());
    player.setInvulnerable(false);
    player.sendMessage(deps.messages().raw("game.sent-to-instance",
        Placeholder.unparsed("id", assignment.gameId().value()),
        Placeholder.component("map", MapNames.display(deps.messages(), assignment.mapId()))));
  }

  private void restoreClaimedLoadout(Player player, MatchAssignment assignment,
      List<ItemInstance> restore, RuntimeException mappingFailure, int attempt) {
    deps.inventories().settleExtraction(player.getUniqueId(), assignment.gameId(), restore)
        .whenComplete((result, error) -> deps.scheduler().runSync(() -> {
          if (error != null || result == null) {
            deps.logger().log(Level.WARNING,
                "恢复未发放的出战装备失败，将在 5 秒后自动重试 (第 " + (attempt + 1)
                    + " 次):" + player.getUniqueId(), error == null ? mappingFailure : error);
            if (player.isOnline() && attempt == 0) {
              player.sendMessage(deps.messages().raw("inventory.loadout-restore-retrying"));
            }
            deps.scheduler().runLater(Duration.ofSeconds(5), () ->
                restoreClaimedLoadout(player, assignment, restore, mappingFailure, attempt + 1));
            return;
          }
          deps.logger().log(Level.SEVERE, "无法把出战装备转换成 Minecraft 物品:"
              + player.getUniqueId(), mappingFailure);
          if (player.isOnline()) {
            player.sendMessage(deps.messages().raw(result.outcome()
                == CraftovInventoryOutcome.EXTRACTED
                ? "inventory.loadout-restored" : "inventory.loadout-closed"));
            leaveAndReturn(player);
          } else {
            deps.games().leave(player.getUniqueId());
          }
        }));
  }

  private void leaveAndReturn(Player player) {
    if (!deps.games().leave(player.getUniqueId()).isSuccess()) {
      LobbyReturn.send(deps.bungee(), deps.config(), deps.messages(), player);
    }
  }

  private void authorizeEditor(Player player) {
    UUID playerId = player.getUniqueId();
    deps.index().setGroup(playerId, "editor:" + playerId);
    release(playerId);
    player.setInvulnerable(false);
    deps.visibility().refresh(player);
    player.sendMessage(deps.messages().raw("join.editor-ready"));
  }

  private static void clearInventory(Player player) {
    player.getInventory().clear();
    player.getInventory().setArmorContents(null);
    player.getInventory().setItemInOffHand(null);
  }

  private void rejectAssignment(Player player, String key, String reason) {
    player.sendMessage(deps.messages().raw(key, Placeholder.unparsed("reason", reason)));
    LobbyReturn.send(deps.bungee(), deps.config(), deps.messages(), player);
  }

  /// May run on the store's IO thread; `java.util.logging` is thread-safe.
  private void logConsumeFailure(Player player, Throwable error) {
    Throwable cause = unwrap(error);
    deps.logger().warning("消费 " + player.getName() + " 的匹配分配失败:" + cause);
  }

  static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  static TargetValidation validateTarget(UUID playerId, MatchAssignment assignment,
      GameServerConfig config) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(assignment, "assignment");
    Preconditions.checkNotNull(config, "config");
    if (!assignment.playerId().equals(playerId)) return TargetValidation.PLAYER_MISMATCH;
    if (!assignment.serverId().equals(config.serverId())) {
      return TargetValidation.SERVER_ID_MISMATCH;
    }
    if (!assignment.proxyName().equals(config.proxyName())) {
      return TargetValidation.PROXY_NAME_MISMATCH;
    }
    return TargetValidation.VALID;
  }

  enum TargetValidation {
    VALID(""),
    PLAYER_MISMATCH("玩家身份不匹配"),
    SERVER_ID_MISMATCH("目标服务器不匹配"),
    PROXY_NAME_MISMATCH("代理后端不匹配");

    private final String playerMessage;

    TargetValidation(String playerMessage) {
      this.playerMessage = playerMessage;
    }

    String playerMessage() {
      return playerMessage;
    }
  }
}
