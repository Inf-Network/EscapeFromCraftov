package com.birditch.escapefromcraftov.game.plugin.instance;

import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.extraction.PlayerExtractedEvent;
import com.birditch.escapefromcraftov.api.event.extraction.PlayerExtractionReadyEvent;
import com.birditch.escapefromcraftov.api.event.extraction.PlayerExtractionLockedEvent;
import com.birditch.escapefromcraftov.api.event.extraction.PlayerExtractionAuthorizationFailedEvent;
import com.birditch.escapefromcraftov.api.event.game.PlayerJoinedCraftovEvent;
import com.birditch.escapefromcraftov.api.event.game.PlayerLeftCraftovEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovCreatedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovSpawnLocation;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.inventory.CraftovInventoryOutcome;
import com.birditch.escapefromcraftov.api.inventory.CraftovSettlementResult;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.report.RaidOutcome;
import com.birditch.escapefromcraftov.game.plugin.secure.SecureSettlementItems;
import com.birditch.escapefromcraftov.game.plugin.extraction.TeamExtractionRoutes;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.LobbyReturn;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.logging.Level;
import java.nio.charset.StandardCharsets;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/// Glues domain game events to Bukkit worlds: creates the instance world when
/// a craftov is created, moves players between lobby and instance (via proxy when
/// enabled, else by teleport), keeps [InstanceWorldIndex] groups and pairwise
/// visibility in sync, and destroys the world when the craftov ends.
///
/// Registered on the domain event bus by the application; all handlers run on
/// the main thread because domain publishing is main-thread only. The world
/// creation callback hops back to the main thread explicitly.
public final class InstanceWorldBridge implements Listener {

  private static final String GAME_GROUP_PREFIX = "game:";
  private static final String LOBBY_GROUP = "lobby";

  private final BridgeDeps deps;
  private final TeamExtractionRoutes extractionRoutes;
  private final Map<CraftovGameId, Integer> nextSpawnByGame = new HashMap<>();
  private final Map<CraftovGameId, Integer> pendingEndAttempts = new HashMap<>();
  private final Map<SettlementKey, PendingExtraction> pendingExtractions = new HashMap<>();

  /// Compatibility constructor for integrations created before team routes.
  public InstanceWorldBridge(BridgeDeps deps) {
    this(deps, new TeamExtractionRoutes());
  }

  public InstanceWorldBridge(BridgeDeps deps, TeamExtractionRoutes extractionRoutes) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
    this.extractionRoutes = Preconditions.checkNotNull(extractionRoutes, "extractionRoutes");
  }

  @Subscribe
  public void onCraftovCreated(CraftovCreatedEvent event) {
    CraftovGameId gameId = event.gameId();
    deps.worlds()
        .createInstanceWorld(gameId, event.mapId())
        .whenComplete(
            (world, error) ->
                deps.scheduler()
                    .runSync(
                        () -> {
                          if (error != null) {
                            deps.logger()
                                .log(
                                    Level.SEVERE,
                                    "World creation failed for game " + gameId.value(),
                                    error);
                            stopFailedGame(gameId);
                            return;
                          }
                          handleWorldReady(gameId, world);
                        }));
  }

  /// Returns every remaining player to the lobby, then tears the instance
  /// world down. Extracted players already went home when their
  /// [PlayerExtractedEvent] fired.
  @Subscribe
  public void onCraftovEnded(CraftovEndedEvent event) {
    extractionRoutes.clear(event.gameId());
    List<UUID> remaining = List.copyOf(event.result().remainingPlayers());
    if (pendingEndAttempts.putIfAbsent(event.gameId(), 0) != null) return;
    for (UUID playerId : remaining) freeze(playerId);
    List<PendingExtraction> extracting = pendingExtractions.entrySet().stream()
        .filter(entry -> entry.getKey().gameId().equals(event.gameId()))
        .map(Map.Entry::getValue).toList();
    java.util.Set<UUID> extractionPlayers = extracting.stream()
        .map(PendingExtraction::playerId).collect(java.util.stream.Collectors.toSet());
    List<UUID> deaths = remaining.stream()
        .filter(playerId -> !extractionPlayers.contains(playerId)).toList();
    Map<UUID, List<ItemInstance>> protectedByPlayer = new HashMap<>();
    deaths.forEach(playerId -> {
      deps.raidReports().declareOutcome(event.gameId(), playerId, RaidOutcome.TIMED_OUT);
      protectedByPlayer.put(playerId,
          deps.secureContainers().beginSettlement(event.gameId(), playerId));
    });
    settleCraftovEnd(event.gameId(), protectedByPlayer, extracting, 0);
  }

  private void settleCraftovEnd(CraftovGameId gameId,
      Map<UUID, List<ItemInstance>> protectedByPlayer,
      List<PendingExtraction> extracting, int attempt) {
    Map<UUID, CompletableFuture<CraftovSettlementResult>> lossSettlements = new HashMap<>();
    protectedByPlayer.forEach((playerId, protectedItems) -> lossSettlements.put(playerId,
        deps.inventories().settleLoss(playerId, gameId, protectedItems)));
    List<CompletableFuture<?>> settlements = new ArrayList<>(lossSettlements.values());
    extracting.stream().map(PendingExtraction::completion).forEach(settlements::add);
    CompletableFuture.allOf(settlements.toArray(CompletableFuture[]::new))
        .whenComplete((_, failure) ->
        deps.scheduler().runSync(() -> {
          if (failure != null) {
            int next = attempt + 1;
            pendingEndAttempts.put(gameId, next);
            deps.scheduler().runLater(Duration.ofSeconds(5),
                () -> settleCraftovEnd(gameId, protectedByPlayer, extracting, next));
            if (attempt == 0) {
              protectedByPlayer.keySet().stream().map(Bukkit::getPlayer)
                  .filter(java.util.Objects::nonNull)
                  .forEach(player -> present("对局结束结算失败提示", () -> player.sendMessage(
                      deps.messages().raw("game.end-settlement-failed"))));
            }
            safeLog(Level.WARNING,
                "对局结束损失结算失败，将在 5 秒后自动重试 (第 " + next + " 次): " + gameId.value(),
                failure);
          } else {
            protectedByPlayer.forEach((playerId, protectedItems) -> {
              critical("关闭超时安全箱结算", () ->
                  deps.secureContainers().completeSettlement(gameId, playerId));
              CraftovSettlementResult result = lossSettlements.get(playerId).join();
              RaidOutcome outcome = result.outcome() == CraftovInventoryOutcome.EXTRACTED
                  ? RaidOutcome.EXTRACTED
                  : deps.raidReports().outcomeOr(gameId, playerId, RaidOutcome.TIMED_OUT);
              critical("写入超时战报", () ->
                  deps.raidReports().complete(gameId, playerId, outcome, protectedItems));
            });
            finishCraftovEnd(gameId, List.copyOf(protectedByPlayer.keySet()));
          }
        }));
  }

  @Subscribe
  public void onPlayerJoinedCraftov(PlayerJoinedCraftovEvent event) {
    deps.index().setGroup(event.playerId(), GAME_GROUP_PREFIX + event.gameId().value());
    Player player = Bukkit.getPlayer(event.playerId());
    if (player == null) {
      return;
    }
    if (deps.index().isReady(event.gameId())) {
      deps.index().worldOf(event.gameId()).map(Bukkit::getWorld)
          .ifPresent(world -> teleportToGeneratedSpawn(player, event.gameId(), world));
    }
    deps.visibility().refresh(player);
  }

  @Subscribe
  public void onPlayerLeftCraftov(PlayerLeftCraftovEvent event) {
    deps.games().find(event.gameId()).ifPresent(extractionRoutes::prune);
    returnToLobby(event.playerId());
  }

  /// Extraction success is a craftov exit like any other: the game manager has
  /// already removed the player from the game, so only the world-side return
  /// remains.
  @Subscribe
  public void onPlayerExtracted(PlayerExtractedEvent event) {
    deps.games().find(event.gameId()).ifPresent(extractionRoutes::prune);
    returnToLobby(event.playerId());
  }

  /// The timer is complete, but leaving the craftov is gated by the durable
  /// inventory transaction. Only a successful settlement publishes the final
  /// PlayerExtractedEvent consumed by the game manager and this bridge.
  @Subscribe
  public void onPlayerExtractionLocked(PlayerExtractionLockedEvent event) {
    SettlementKey key = new SettlementKey(event.gameId(), event.playerId());
    if (pendingExtractions.containsKey(key)) return;
    Player player = Bukkit.getPlayer(event.playerId());
    if (player == null) {
      critical("终止离线玩家撤离会话", () ->
          deps.extractions().failSettlement(event.playerId(), event.gameId(), event.pointId()));
      beginLockedFailure(event.gameId(), event.playerId(), null);
      safeLog(Level.WARNING, "撤离锁定时玩家已离线，已按行动失败结算:" + event.playerId(), null);
      return;
    }
    freeze(event.playerId());
    List<ItemInstance> carried;
    try {
      carried = settlementItems(player, event.gameId());
    } catch (RuntimeException invalidSnapshot) {
      rejectInvalidExtractionSnapshot(event.gameId(), event.playerId(), event.pointId(),
          invalidSnapshot);
      return;
    }
    pendingExtractions.put(key, new PendingExtraction(event.gameId(), event.playerId(),
        event.pointId(), carried, new CompletableFuture<>()));
  }

  @Subscribe
  public void onPlayerExtractionAuthorizationFailed(
      PlayerExtractionAuthorizationFailedEvent event) {
    SettlementKey key = new SettlementKey(event.gameId(), event.playerId());
    PendingExtraction pending = pendingExtractions.remove(key);
    if (pending != null) {
      boolean running = deps.games().find(event.gameId())
          .filter(game -> game.state() == CraftovGameState.RUNNING).isPresent();
      if (running) {
        critical("取消失败撤离的安全箱锁定", () ->
            deps.secureContainers().cancelSettlement(event.gameId(), event.playerId()));
        pending.completion().complete(CraftovInventoryOutcome.LOST);
      } else {
        beginLockedFailure(event.gameId(), event.playerId(), pending.completion());
      }
    }
    Player player = Bukkit.getPlayer(event.playerId());
    if (player != null) {
      present("撤离授权失败提示", () -> {
        player.setInvulnerable(false);
        player.sendMessage(deps.messages().raw("extraction.authorization-failed",
            Placeholder.unparsed("reason", event.reason())));
      });
    }
  }

  @Subscribe
  public void onPlayerExtractionReady(PlayerExtractionReadyEvent event) {
    SettlementKey key = new SettlementKey(event.gameId(), event.playerId());
    PendingExtraction pending = pendingExtractions.get(key);
    if (pending == null) {
      Player player = Bukkit.getPlayer(event.playerId());
      if (player == null) {
        critical("终止无快照玩家撤离会话", () ->
            deps.extractions().failSettlement(event.playerId(), event.gameId(), event.pointId()));
        beginLockedFailure(event.gameId(), event.playerId(), null);
        safeLog(Level.WARNING, "撤离授权完成时没有可用的背包快照，已按行动失败结算:"
            + event.playerId(), null);
        return;
      }
      freeze(event.playerId());
      List<ItemInstance> carried;
      try {
        carried = settlementItems(player, event.gameId());
      } catch (RuntimeException invalidSnapshot) {
        rejectInvalidExtractionSnapshot(event.gameId(), event.playerId(), event.pointId(),
            invalidSnapshot);
        return;
      }
      pending = new PendingExtraction(event.gameId(), event.playerId(), event.pointId(), carried,
          new CompletableFuture<>());
      pendingExtractions.put(key, pending);
    }
    if (!pending.pointId().equals(event.pointId())) {
      pendingExtractions.remove(key, pending);
      critical("终止错位撤离会话", () ->
          deps.extractions().failSettlement(event.playerId(), event.gameId(), event.pointId()));
      beginLockedFailure(event.gameId(), event.playerId(), pending.completion());
      safeLog(Level.SEVERE, "撤离快照与授权点不一致，已拒绝并转为失败结算:"
          + event.playerId(), null);
      return;
    }
    settleExtraction(key, pending, 0);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onSettlingMove(PlayerMoveEvent event) {
    if (!settling(event.getPlayer())) return;
    Location from = event.getFrom();
    Location to = event.getTo();
    if (to != null && (from.getX() != to.getX() || from.getY() != to.getY()
        || from.getZ() != to.getZ())) {
      event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(),
          to.getYaw(), to.getPitch()));
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onCraftovItemDrop(PlayerDropItemEvent event) {
    // Active grid carry owns partial-stack identity splitting at its later MONITOR commit.
    if (deps.carry().active(event.getPlayer().getUniqueId())) return;
    ItemStack dropped = event.getItemDrop().getItemStack();
    var tagged = deps.itemStacks().read(dropped).orElse(null);
    if (tagged == null || !containsInstance(event.getPlayer(), tagged.instanceId())) return;
    deps.itemStacks().assignFreshIdentity(dropped)
        .ifPresent(_ -> event.getItemDrop().setItemStack(dropped));
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onCraftovDeathDrops(PlayerDeathEvent event) {
    Set<UUID> identities = new HashSet<>();
    for (ItemStack dropped : event.getDrops()) {
      var tagged = deps.itemStacks().read(dropped).orElse(null);
      if (tagged == null || identities.add(tagged.instanceId())) continue;
      deps.itemStacks().assignFreshIdentity(dropped).ifPresent(identities::add);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onSettlingDrop(PlayerDropItemEvent event) {
    if (settling(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onSettlingSwap(PlayerSwapHandItemsEvent event) {
    if (settling(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onSettlingInventoryClick(InventoryClickEvent event) {
    if (event.getWhoClicked() instanceof Player player && settling(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onSettlingInventoryDrag(InventoryDragEvent event) {
    if (event.getWhoClicked() instanceof Player player && settling(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onSettlingInteract(PlayerInteractEvent event) {
    if (settling(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onSettlingInteractEntity(PlayerInteractEntityEvent event) {
    if (settling(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onSettlingPlace(BlockPlaceEvent event) {
    if (settling(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onSettlingPickup(EntityPickupItemEvent event) {
    if (event.getEntity() instanceof Player player && settling(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
  public void onSettlingDamage(EntityDamageByEntityEvent event) {
    Player attacker = attacker(event.getDamager());
    if (attacker != null && settling(attacker)) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
  public void onSettlingIncomingDamage(EntityDamageEvent event) {
    if (event.getEntity() instanceof Player player && settling(player)) {
      event.setCancelled(true);
    }
  }

  private void settleExtraction(SettlementKey key, PendingExtraction pending, int attempt) {
    if (pending.completion().isDone()) return;
    deps.inventories().settleExtraction(pending.playerId(), pending.gameId(), pending.carried())
        .whenComplete((result, failure) -> deps.scheduler().runSync(() -> {
          if (failure != null || result == null) {
            deps.scheduler().runLater(Duration.ofSeconds(5),
                () -> settleExtraction(key, pending, attempt + 1));
            if (attempt == 0) {
              Player online = Bukkit.getPlayer(pending.playerId());
              if (online != null) {
                present("撤离结算失败提示", () ->
                    online.sendMessage(deps.messages().raw("extraction.settlement-failed")));
              }
            }
            safeLog(Level.WARNING,
                "撤离物资结算失败，将在 5 秒后自动重试 (第 " + (attempt + 1) + " 次):"
                    + pending.playerId(), failure);
            return;
          }
          if (result.outcome() == CraftovInventoryOutcome.EXTRACTED) {
            awardExtractionProgression(key, pending, result, 0);
          } else {
            finishExtractionSettlement(key, pending, result);
          }
        }));
  }

  private void awardExtractionProgression(SettlementKey key, PendingExtraction pending,
      CraftovSettlementResult result, int attempt) {
    UUID transactionId = UUID.nameUUIDFromBytes(("craftov-progression:extraction:"
        + pending.gameId().value() + ":" + pending.playerId())
        .getBytes(StandardCharsets.UTF_8));
    deps.progression().award(transactionId, pending.playerId(), deps.extractionExperience(),
        "成功撤离").whenComplete((update, failure) -> deps.scheduler().runSync(() -> {
          if (failure != null || update == null || !update.isSuccess()) {
            deps.scheduler().runLater(Duration.ofSeconds(5), () ->
                awardExtractionProgression(key, pending, result, attempt + 1));
            safeLog(Level.WARNING,
                "撤离经验写入失败，将在 5 秒后重试 (第 " + (attempt + 1) + " 次):"
                    + pending.playerId(), failure);
            return;
          }
          update.value().filter(value -> value.levelledUp()).ifPresent(value -> {
            Player online = Bukkit.getPlayer(pending.playerId());
            if (online != null) present("撤离升级提示", () -> online.sendMessage(Component.text(
                "行动等级提升至 " + value.levelAfter(), NamedTextColor.GOLD)));
          });
          finishExtractionSettlement(key, pending, result);
        }));
  }

  private void finishExtractionSettlement(SettlementKey key, PendingExtraction pending,
      CraftovSettlementResult result) {
    pendingExtractions.remove(key, pending);
    critical("关闭安全箱结算", () ->
        deps.secureContainers().completeSettlement(pending.gameId(), pending.playerId()));
    Player online = Bukkit.getPlayer(pending.playerId());
    if (result.outcome() == CraftovInventoryOutcome.EXTRACTED) {
      critical("写入撤离战报", () -> deps.raidReports().complete(pending.gameId(),
          pending.playerId(), RaidOutcome.EXTRACTED, pending.carried()));
      critical("完成撤离领域会话", () -> {
        if (!deps.extractions().completeSettlement(
            pending.playerId(), pending.gameId(), pending.pointId())) {
          deps.logger().warning("撤离仓库事务已成功，但领域会话已不存在:" + pending.playerId());
        }
      });
      if (online != null) {
        critical("清理已撤离玩家物品", () -> clearInventory(online));
      }
      pending.completion().complete(CraftovInventoryOutcome.EXTRACTED);
      if (online != null) present("撤离成功提示", () -> {
        online.showTitle(Title.title(Component.text("撤离成功", NamedTextColor.GREEN),
            Component.text("战利品已送回物资仓库", NamedTextColor.GRAY)));
        online.sendMessage(deps.messages().raw("extraction.completed"));
      });
      try {
        deps.eventBus().publish(new PlayerExtractedEvent(
            pending.gameId(), pending.playerId(), pending.pointId()));
      } catch (RuntimeException publishFailure) {
        leaveOrReturnToLobby(pending.playerId(), "撤离事件失败后的直接离场");
        safeLog(Level.SEVERE, "撤离事件发布失败，已执行直接离场兜底:" + pending.playerId(),
            publishFailure);
      }
      return;
    }

    critical("终止冲突撤离会话", () ->
        deps.extractions().failSettlement(pending.playerId(), pending.gameId(), pending.pointId()));
    pending.completion().complete(CraftovInventoryOutcome.LOST);
    safeLog(Level.SEVERE, "撤离结算与既有死亡结算冲突，按数据库既有结果处理:"
        + pending.playerId(), null);
    if (online != null) {
      critical("清理冲突结算玩家物品", () -> clearInventory(online));
      present("撤离冲突提示", () ->
          online.sendMessage(deps.messages().raw("extraction.settlement-conflict")));
    }
    leaveOrReturnToLobby(pending.playerId(), "冲突结算后的直接离场");
  }

  private void leaveOrReturnToLobby(UUID playerId, String action) {
    boolean left = false;
    RuntimeException leaveFailure = null;
    try {
      left = deps.games().leave(playerId).isSuccess();
    } catch (RuntimeException failure) {
      leaveFailure = failure;
    }
    if (!left) critical(action + "的大厅返回", () -> returnToLobby(playerId));
    if (leaveFailure != null) {
      safeLog(Level.SEVERE, action + "失败，已执行大厅返回兜底:" + playerId, leaveFailure);
    }
  }

  private void present(String action, Runnable presentation) {
    try {
      presentation.run();
    } catch (RuntimeException failure) {
      safeLog(Level.WARNING, action + "失败，但结算继续", failure);
    }
  }

  private void critical(String action, Runnable operation) {
    try {
      operation.run();
    } catch (RuntimeException failure) {
      safeLog(Level.SEVERE, action + "失败，继续执行结算终态", failure);
    }
  }

  private void safeLog(Level level, String message, Throwable failure) {
    try {
      deps.logger().log(level, message, failure);
    } catch (RuntimeException ignored) {
      // Logging and handlers attached to it must never become a settlement dependency.
    }
  }

  private void beginLockedFailure(CraftovGameId gameId, UUID playerId,
      CompletableFuture<CraftovInventoryOutcome> completion) {
    deps.raidReports().declareOutcome(gameId, playerId, RaidOutcome.FAILED);
    List<ItemInstance> protectedItems = deps.secureContainers().beginSettlement(gameId, playerId);
    settleLockedFailure(gameId, playerId, protectedItems, completion, 0);
  }

  private void settleLockedFailure(CraftovGameId gameId, UUID playerId,
      List<ItemInstance> protectedItems,
      CompletableFuture<CraftovInventoryOutcome> completion, int attempt) {
    deps.inventories().settleLoss(playerId, gameId, protectedItems)
        .whenComplete((result, failure) ->
        deps.scheduler().runSync(() -> {
          if (failure != null || result == null) {
            deps.scheduler().runLater(Duration.ofSeconds(5),
                () -> settleLockedFailure(gameId, playerId, protectedItems,
                    completion, attempt + 1));
            safeLog(Level.WARNING,
                "锁定撤离失败后的损失结算失败，将在 5 秒后重试 (第 " + (attempt + 1)
                    + " 次):" + playerId, failure);
            return;
          }
          critical("关闭失败撤离的安全箱结算", () ->
              deps.secureContainers().completeSettlement(gameId, playerId));
          RaidOutcome outcome = result.outcome() == CraftovInventoryOutcome.EXTRACTED
              ? RaidOutcome.EXTRACTED
              : deps.raidReports().outcomeOr(gameId, playerId, RaidOutcome.FAILED);
          critical("写入失败撤离战报", () ->
              deps.raidReports().complete(gameId, playerId, outcome, protectedItems));
          if (completion != null) completion.complete(result.outcome());
          leaveOrReturnToLobby(playerId, "失败撤离后的直接离场");
        }));
  }

  private void handleWorldReady(CraftovGameId gameId, World world) {
    if (deps.games().find(gameId).isEmpty()) {
      deps.logger()
          .warning("Game " + gameId.value() + " ended before its world finished; discarding it");
      deps.worlds().destroyInstanceWorld(gameId);
      return;
    }
    deps.index().register(gameId, world.getName());
    deps.index().markReady(gameId);
    deps.games().find(gameId).ifPresent(game -> game.players().forEach(craftovPlayer -> {
      Player player = Bukkit.getPlayer(craftovPlayer.playerId());
      if (player != null) teleportToGeneratedSpawn(player, gameId, world);
    }));
  }

  private void teleportToGeneratedSpawn(Player player, CraftovGameId gameId, World world) {
    var game = deps.games().find(gameId).orElse(null);
    List<CraftovSpawnLocation> spawns = game == null ? List.of() : game.spawnLocations();
    int next = nextSpawnByGame.getOrDefault(gameId, 0);
    nextSpawnByGame.put(gameId, next + 1);
    int teamId = game == null ? 0 : game.players().stream()
        .filter(candidate -> candidate.playerId().equals(player.getUniqueId()))
        .findFirst().flatMap(candidate -> candidate.teamId().isPresent()
            ? java.util.Optional.of(candidate.teamId().getAsInt()) : java.util.Optional.empty())
        .orElse(0);
    if (game != null && teamId > 0) {
      var resolved = extractionRoutes.resolvedSpawn(gameId, teamId).orElse(null);
      if (resolved != null) {
        Location location = new Location(world, resolved.x(), resolved.y(), resolved.z(),
            resolved.yaw(), resolved.pitch());
        player.teleport(location);
        extractionRoutes.freezeRoute(game, teamId, location.getX(), location.getZ());
        return;
      }
    }
    if (spawns.isEmpty()) {
      teleportToTeamFallback(player, game, teamId, world);
      return;
    }
    // The team's first arrival freezes one authored spawn. Teammates reuse its
    // anchor/region instead of consuming the room-wide spawn ticket and being
    // scattered to another side of the map.
    CraftovSpawnLocation point = teamId > 0
        ? extractionRoutes.spawn(gameId, teamId).orElseGet(() -> {
          CraftovSpawnLocation selected = CraftovSpawnSelector
              .select(spawns, teamId, teamId - 1L,
                  extractionRoutes.reservedAuthoredSpawnIds(gameId)).orElse(null);
          return selected == null ? null
              : extractionRoutes.rememberSpawn(gameId, teamId, selected);
        })
        : CraftovSpawnSelector.select(spawns, teamId, next).orElse(null);
    if (point == null) {
      teleportToTeamFallback(player, game, teamId, world);
      return;
    }
    long seed = ((long) gameId.value().hashCode() << 32)
        ^ player.getUniqueId().getMostSignificantBits()
        ^ player.getUniqueId().getLeastSignificantBits() ^ next;
    Location safe = CraftovSpawnResolver.resolve(world, point, seed).orElse(null);
    if (safe == null) {
      deps.logger().warning("出生区域没有找到安全落脚点，已使用世界出生点: "
          + gameId.value() + "/" + point.id());
      teleportToTeamFallback(player, game, teamId, world);
      return;
    }
    if (game != null && teamId > 0) {
      var resolved = extractionRoutes.rememberResolvedSpawn(game.id(), teamId,
          safe.getX(), safe.getY(), safe.getZ(), safe.getYaw(), safe.getPitch(), false);
      safe = new Location(world, resolved.x(), resolved.y(), resolved.z(),
          resolved.yaw(), resolved.pitch());
      extractionRoutes.freezeRoute(game, teamId, safe.getX(), safe.getZ());
    }
    player.teleport(safe);
  }

  private void teleportToTeamFallback(
      Player player, com.birditch.escapefromcraftov.api.game.CraftovGame game,
      int teamId, World world) {
    Location fallback = world.getSpawnLocation();
    if (game != null && teamId > 0) {
      var frozen = extractionRoutes.rememberResolvedSpawn(game.id(), teamId,
          fallback.getX(), fallback.getY(), fallback.getZ(), fallback.getYaw(),
          fallback.getPitch(), true);
      fallback = new Location(world, frozen.x(), frozen.y(), frozen.z(),
          frozen.yaw(), frozen.pitch());
      extractionRoutes.freezeRoute(game, teamId, fallback.getX(), fallback.getZ());
    }
    player.teleport(fallback);
  }

  private void stopFailedGame(CraftovGameId gameId) {
    deps.games()
        .stop(gameId)
        .error()
        .ifPresent(
            message ->
                deps.logger()
                    .warning(
                        "Could not stop game "
                            + gameId.value()
                            + " after world failure: "
                            + message));
  }

  private void returnToLobby(UUID playerId) {
    deps.index().setGroup(playerId, LOBBY_GROUP);
    Player player = Bukkit.getPlayer(playerId);
    if (player == null) {
      // An offline leave still changed the room population. Refresh the
      // remaining viewers so their Tab footer and room list are not stale.
      deps.visibility().refreshAll();
      return;
    }
    player.setInvulnerable(false);
    deps.visibility().refresh(player);
    deps.profiles().save(playerId).orTimeout(10, TimeUnit.SECONDS)
        .whenComplete((_, failure) -> deps.scheduler().runSync(() -> {
          Player online = Bukkit.getPlayer(playerId);
          if (online == null) return;
          if (failure != null) {
            safeLog(Level.SEVERE, "返回大厅前保存玩家档案失败:" + playerId, failure);
            present("返回大厅存档失败提示", () ->
                online.sendMessage(deps.messages().raw("game.return-save-failed")));
            return;
          }
          LobbyReturn.send(deps.bungee(), deps.config(), deps.messages(), online);
        }));
  }

  private void finishCraftovEnd(CraftovGameId gameId, List<UUID> remaining) {
    pendingEndAttempts.remove(gameId);
    for (UUID playerId : remaining) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null) critical("清理超时结算玩家物品", () -> clearInventory(player));
      critical("超时结算返回大厅", () -> returnToLobby(playerId));
    }
    critical("销毁已结算实例世界", () -> deps.worlds().destroyInstanceWorld(gameId));
    critical("移除已结算实例索引", () -> deps.index().remove(gameId));
    nextSpawnByGame.remove(gameId);
  }

  private void freeze(UUID playerId) {
    Player player = Bukkit.getPlayer(playerId);
    if (player != null) {
      if (!deps.menus().closeCurrent(player)) {
        safeLog(Level.WARNING, "无法通过 TrMenu 关闭 " + player.getName() + " 的当前菜单", null);
      }
      player.setInvulnerable(true);
    }
  }

  private static void clearInventory(Player player) {
    player.getInventory().clear();
    player.getInventory().setArmorContents(null);
    player.getInventory().setItemInOffHand(null);
  }

  private List<ItemInstance> settlementItems(Player player, CraftovGameId gameId) {
    return SecureSettlementItems.merge(
        deps.carry().settlementSnapshot(player, gameId).settlementItems(),
        deps.secureContainers().beginSettlement(gameId, player.getUniqueId()));
  }

  private void rejectInvalidExtractionSnapshot(CraftovGameId gameId, UUID playerId,
      ExtractionPointId pointId, RuntimeException failure) {
    critical("终止无效快照撤离会话", () ->
        deps.extractions().failSettlement(playerId, gameId, pointId));
    beginLockedFailure(gameId, playerId, null);
    safeLog(Level.SEVERE,
        "撤离快照包含无效或重复的物品实例，已按行动失败结算:" + playerId, failure);
  }

  private boolean containsInstance(Player player, UUID instanceId) {
    List<ItemStack> contents = new ArrayList<>();
    java.util.Collections.addAll(contents, player.getInventory().getStorageContents());
    java.util.Collections.addAll(contents, player.getInventory().getArmorContents());
    contents.add(player.getInventory().getItemInOffHand());
    return contents.stream().anyMatch(stack -> deps.itemStacks().read(stack)
        .map(tagged -> tagged.instanceId().equals(instanceId)).orElse(false));
  }

  private boolean settling(Player player) {
    UUID playerId = player.getUniqueId();
    return pendingExtractions.keySet().stream().anyMatch(key -> key.playerId().equals(playerId));
  }

  private static Player attacker(Entity entity) {
    if (entity instanceof Player player) return player;
    if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
      return player;
    }
    return null;
  }

  private record SettlementKey(CraftovGameId gameId, UUID playerId) {}

  private record PendingExtraction(
      CraftovGameId gameId,
      UUID playerId,
      ExtractionPointId pointId,
      List<ItemInstance> carried,
      CompletableFuture<CraftovInventoryOutcome> completion) {

    private PendingExtraction {
      carried = List.copyOf(carried);
    }
  }
}
