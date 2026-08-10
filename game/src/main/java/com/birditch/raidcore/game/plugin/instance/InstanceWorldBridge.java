package com.birditch.raidcore.game.plugin.instance;

import com.birditch.raidcore.api.event.Subscribe;
import com.birditch.raidcore.api.event.extraction.PlayerExtractedEvent;
import com.birditch.raidcore.api.event.extraction.PlayerExtractionReadyEvent;
import com.birditch.raidcore.api.event.extraction.PlayerExtractionLockedEvent;
import com.birditch.raidcore.api.event.extraction.PlayerExtractionAuthorizationFailedEvent;
import com.birditch.raidcore.api.event.game.PlayerJoinedRaidEvent;
import com.birditch.raidcore.api.event.game.PlayerLeftRaidEvent;
import com.birditch.raidcore.api.event.game.RaidCreatedEvent;
import com.birditch.raidcore.api.event.game.RaidEndedEvent;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidSpawnLocation;
import com.birditch.raidcore.api.extraction.ExtractionPointId;
import com.birditch.raidcore.api.inventory.RaidInventoryOutcome;
import com.birditch.raidcore.api.inventory.RaidSettlementResult;
import com.birditch.raidcore.api.item.ItemInstance;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.game.plugin.LobbyReturn;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
/// a raid is created, moves players between lobby and instance (via proxy when
/// enabled, else by teleport), keeps [InstanceWorldIndex] groups and pairwise
/// visibility in sync, and destroys the world when the raid ends.
///
/// Registered on the domain event bus by the application; all handlers run on
/// the main thread because domain publishing is main-thread only. The world
/// creation callback hops back to the main thread explicitly.
public final class InstanceWorldBridge implements Listener {

  private static final String GAME_GROUP_PREFIX = "game:";
  private static final String LOBBY_GROUP = "lobby";

  private final BridgeDeps deps;
  private final Map<RaidGameId, Integer> nextSpawnByGame = new HashMap<>();
  private final Map<RaidGameId, Integer> pendingEndAttempts = new HashMap<>();
  private final Map<SettlementKey, PendingExtraction> pendingExtractions = new HashMap<>();

  public InstanceWorldBridge(BridgeDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
  }

  @Subscribe
  public void onRaidCreated(RaidCreatedEvent event) {
    RaidGameId gameId = event.gameId();
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
  public void onRaidEnded(RaidEndedEvent event) {
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
    settleRaidEnd(event.gameId(), deaths, extracting, 0);
  }

  private void settleRaidEnd(RaidGameId gameId, List<UUID> deaths,
      List<PendingExtraction> extracting, int attempt) {
    List<CompletableFuture<?>> settlements = new ArrayList<>();
    deaths.stream().map(playerId -> deps.inventories().settleDeath(playerId, gameId))
        .forEach(settlements::add);
    extracting.stream().map(PendingExtraction::completion).forEach(settlements::add);
    CompletableFuture.allOf(settlements.toArray(CompletableFuture[]::new))
        .whenComplete((_, failure) ->
        deps.scheduler().runSync(() -> {
          if (failure != null) {
            int next = attempt + 1;
            pendingEndAttempts.put(gameId, next);
            if (attempt == 0) {
              deaths.stream().map(Bukkit::getPlayer).filter(java.util.Objects::nonNull)
                  .forEach(player -> player.sendMessage(
                      deps.messages().raw("game.end-settlement-failed")));
            }
            deps.logger().log(Level.WARNING,
                "对局结束损失结算失败，将在 5 秒后自动重试 (第 " + next + " 次): " + gameId.value(),
                failure);
            deps.scheduler().runLater(Duration.ofSeconds(5),
                () -> settleRaidEnd(gameId, deaths, extracting, next));
          } else {
            finishRaidEnd(gameId, deaths);
          }
        }));
  }

  @Subscribe
  public void onPlayerJoinedRaid(PlayerJoinedRaidEvent event) {
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
  public void onPlayerLeftRaid(PlayerLeftRaidEvent event) {
    returnToLobby(event.playerId());
  }

  /// Extraction success is a raid exit like any other: the game manager has
  /// already removed the player from the game, so only the world-side return
  /// remains.
  @Subscribe
  public void onPlayerExtracted(PlayerExtractedEvent event) {
    returnToLobby(event.playerId());
  }

  /// The timer is complete, but leaving the raid is gated by the durable
  /// inventory transaction. Only a successful settlement publishes the final
  /// PlayerExtractedEvent consumed by the game manager and this bridge.
  @Subscribe
  public void onPlayerExtractionLocked(PlayerExtractionLockedEvent event) {
    SettlementKey key = new SettlementKey(event.gameId(), event.playerId());
    if (pendingExtractions.containsKey(key)) return;
    Player player = Bukkit.getPlayer(event.playerId());
    if (player == null) {
      deps.logger().warning("撤离锁定时玩家已离线，按行动失败结算:" + event.playerId());
      deps.extractions().failSettlement(event.playerId(), event.gameId(), event.pointId());
      settleLockedFailure(event.gameId(), event.playerId(), null, 0);
      return;
    }
    freeze(event.playerId());
    pendingExtractions.put(key, new PendingExtraction(event.gameId(), event.playerId(),
        event.pointId(), scanCarriedItems(player), new CompletableFuture<>()));
  }

  @Subscribe
  public void onPlayerExtractionAuthorizationFailed(
      PlayerExtractionAuthorizationFailedEvent event) {
    SettlementKey key = new SettlementKey(event.gameId(), event.playerId());
    PendingExtraction pending = pendingExtractions.remove(key);
    Player player = Bukkit.getPlayer(event.playerId());
    if (player != null) {
      player.setInvulnerable(false);
      player.sendMessage(deps.messages().raw("extraction.authorization-failed",
          Placeholder.unparsed("reason", event.reason())));
    }
    if (pending == null) return;
    boolean running = deps.games().find(event.gameId())
        .filter(game -> game.state() == RaidGameState.RUNNING).isPresent();
    if (running) {
      pending.completion().complete(RaidInventoryOutcome.LOST);
    } else {
      settleLockedFailure(event.gameId(), event.playerId(), pending.completion(), 0);
    }
  }

  @Subscribe
  public void onPlayerExtractionReady(PlayerExtractionReadyEvent event) {
    SettlementKey key = new SettlementKey(event.gameId(), event.playerId());
    PendingExtraction pending = pendingExtractions.get(key);
    if (pending == null) {
      Player player = Bukkit.getPlayer(event.playerId());
      if (player == null) {
        deps.logger().warning("撤离授权完成时没有可用的背包快照，按行动失败结算:"
            + event.playerId());
        deps.extractions().failSettlement(event.playerId(), event.gameId(), event.pointId());
        settleLockedFailure(event.gameId(), event.playerId(), null, 0);
        return;
      }
      freeze(event.playerId());
      pending = new PendingExtraction(event.gameId(), event.playerId(), event.pointId(),
          scanCarriedItems(player), new CompletableFuture<>());
      pendingExtractions.put(key, pending);
    }
    if (!pending.pointId().equals(event.pointId())) {
      deps.logger().severe("撤离快照与授权点不一致，拒绝继续结算:" + event.playerId());
      pendingExtractions.remove(key, pending);
      deps.extractions().failSettlement(event.playerId(), event.gameId(), event.pointId());
      settleLockedFailure(event.gameId(), event.playerId(), pending.completion(), 0);
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

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onSettlingDamage(EntityDamageByEntityEvent event) {
    Player attacker = attacker(event.getDamager());
    if (attacker != null && settling(attacker)) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
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
            if (attempt == 0) {
              Player online = Bukkit.getPlayer(pending.playerId());
              if (online != null) {
                online.sendMessage(deps.messages().raw("extraction.settlement-failed"));
              }
            }
            deps.logger().log(Level.WARNING,
                "撤离物资结算失败，将在 5 秒后自动重试 (第 " + (attempt + 1) + " 次):"
                    + pending.playerId(), failure);
            deps.scheduler().runLater(Duration.ofSeconds(5),
                () -> settleExtraction(key, pending, attempt + 1));
            return;
          }
          if (result.outcome() == RaidInventoryOutcome.EXTRACTED) {
            awardExtractionProgression(key, pending, result, 0);
          } else {
            finishExtractionSettlement(key, pending, result);
          }
        }));
  }

  private void awardExtractionProgression(SettlementKey key, PendingExtraction pending,
      RaidSettlementResult result, int attempt) {
    UUID transactionId = UUID.nameUUIDFromBytes(("raid-progression:extraction:"
        + pending.gameId().value() + ":" + pending.playerId())
        .getBytes(StandardCharsets.UTF_8));
    deps.progression().award(transactionId, pending.playerId(), deps.extractionExperience(),
        "成功撤离").whenComplete((update, failure) -> deps.scheduler().runSync(() -> {
          if (failure != null || update == null || !update.isSuccess()) {
            deps.logger().log(Level.WARNING,
                "撤离经验写入失败，将在 5 秒后重试 (第 " + (attempt + 1) + " 次):"
                    + pending.playerId(), failure);
            deps.scheduler().runLater(Duration.ofSeconds(5), () ->
                awardExtractionProgression(key, pending, result, attempt + 1));
            return;
          }
          update.value().filter(value -> value.levelledUp()).ifPresent(value -> {
            Player online = Bukkit.getPlayer(pending.playerId());
            if (online != null) online.sendMessage(Component.text(
                "行动等级提升至 " + value.levelAfter(), NamedTextColor.GOLD));
          });
          finishExtractionSettlement(key, pending, result);
        }));
  }

  private void finishExtractionSettlement(SettlementKey key, PendingExtraction pending,
      RaidSettlementResult result) {
    pendingExtractions.remove(key, pending);
    Player online = Bukkit.getPlayer(pending.playerId());
    if (result.outcome() == RaidInventoryOutcome.EXTRACTED) {
      if (!deps.extractions().completeSettlement(
          pending.playerId(), pending.gameId(), pending.pointId())) {
        deps.logger().warning("撤离仓库事务已成功，但领域会话已不存在:" + pending.playerId());
      }
      if (online != null) {
        clearInventory(online);
        online.showTitle(Title.title(Component.text("撤离成功", NamedTextColor.GREEN),
            Component.text("战利品已送回物资仓库", NamedTextColor.GRAY)));
        online.sendMessage(deps.messages().raw("extraction.completed"));
      }
      pending.completion().complete(RaidInventoryOutcome.EXTRACTED);
      deps.eventBus().publish(new PlayerExtractedEvent(
          pending.gameId(), pending.playerId(), pending.pointId()));
      return;
    }

    deps.extractions().failSettlement(pending.playerId(), pending.gameId(), pending.pointId());
    pending.completion().complete(RaidInventoryOutcome.LOST);
    deps.logger().severe("撤离结算与既有死亡结算冲突，按数据库既有结果处理:"
        + pending.playerId());
    if (online != null) {
      clearInventory(online);
      online.sendMessage(deps.messages().raw("extraction.settlement-conflict"));
    }
    if (!deps.games().leave(pending.playerId()).isSuccess()) {
      returnToLobby(pending.playerId());
    }
  }

  private void settleLockedFailure(RaidGameId gameId, UUID playerId,
      CompletableFuture<RaidInventoryOutcome> completion, int attempt) {
    deps.inventories().settleDeath(playerId, gameId).whenComplete((_, failure) ->
        deps.scheduler().runSync(() -> {
          if (failure != null) {
            deps.logger().log(Level.WARNING,
                "锁定撤离失败后的损失结算失败，将在 5 秒后重试 (第 " + (attempt + 1)
                    + " 次):" + playerId, failure);
            deps.scheduler().runLater(Duration.ofSeconds(5),
                () -> settleLockedFailure(gameId, playerId, completion, attempt + 1));
            return;
          }
          if (completion != null) completion.complete(RaidInventoryOutcome.LOST);
          if (!deps.games().leave(playerId).isSuccess()) returnToLobby(playerId);
        }));
  }

  private void handleWorldReady(RaidGameId gameId, World world) {
    if (deps.games().find(gameId).isEmpty()) {
      deps.logger()
          .warning("Game " + gameId.value() + " ended before its world finished; discarding it");
      deps.worlds().destroyInstanceWorld(gameId);
      return;
    }
    deps.index().register(gameId, world.getName());
    deps.index().markReady(gameId);
    deps.games().find(gameId).ifPresent(game -> game.players().forEach(raidPlayer -> {
      Player player = Bukkit.getPlayer(raidPlayer.playerId());
      if (player != null) teleportToGeneratedSpawn(player, gameId, world);
    }));
  }

  private void teleportToGeneratedSpawn(Player player, RaidGameId gameId, World world) {
    var game = deps.games().find(gameId).orElse(null);
    List<RaidSpawnLocation> spawns = game == null ? List.of() : game.spawnLocations();
    if (spawns.isEmpty()) {
      player.teleport(world.getSpawnLocation());
      return;
    }
    int next = nextSpawnByGame.getOrDefault(gameId, 0);
    nextSpawnByGame.put(gameId, next + 1);
    int teamId = game.players().stream()
        .filter(candidate -> candidate.playerId().equals(player.getUniqueId()))
        .findFirst().flatMap(candidate -> candidate.teamId().isPresent()
            ? java.util.Optional.of(candidate.teamId().getAsInt()) : java.util.Optional.empty())
        .orElse(0);
    RaidSpawnLocation point = RaidSpawnSelector.select(spawns, teamId, next)
        .orElse(null);
    if (point == null) {
      player.teleport(world.getSpawnLocation());
      return;
    }
    long seed = ((long) gameId.value().hashCode() << 32)
        ^ player.getUniqueId().getMostSignificantBits()
        ^ player.getUniqueId().getLeastSignificantBits() ^ next;
    Location safe = RaidSpawnResolver.resolve(world, point, seed).orElse(null);
    if (safe == null) {
      deps.logger().warning("出生区域没有找到安全落脚点，已使用世界出生点: "
          + gameId.value() + "/" + point.id());
      player.teleport(world.getSpawnLocation());
      return;
    }
    player.teleport(safe);
  }

  private void stopFailedGame(RaidGameId gameId) {
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
            deps.logger().log(Level.SEVERE,
                "返回大厅前保存玩家档案失败:" + playerId, failure);
            online.sendMessage(deps.messages().raw("game.return-save-failed"));
            return;
          }
          LobbyReturn.send(deps.bungee(), deps.config(), deps.messages(), online);
        }));
  }

  private void finishRaidEnd(RaidGameId gameId, List<UUID> remaining) {
    pendingEndAttempts.remove(gameId);
    for (UUID playerId : remaining) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null) clearInventory(player);
      returnToLobby(playerId);
    }
    deps.worlds().destroyInstanceWorld(gameId);
    deps.index().remove(gameId);
    nextSpawnByGame.remove(gameId);
  }

  private void freeze(UUID playerId) {
    Player player = Bukkit.getPlayer(playerId);
    if (player != null) {
      if (!deps.menus().closeCurrent(player)) {
        deps.logger().warning("无法通过 TrMenu 关闭 " + player.getName() + " 的当前菜单");
      }
      player.setInvulnerable(true);
    }
  }

  private static void clearInventory(Player player) {
    player.getInventory().clear();
    player.getInventory().setArmorContents(null);
    player.getInventory().setItemInOffHand(null);
  }

  private List<ItemInstance> scanCarriedItems(Player player) {
    List<ItemStack> contents = new ArrayList<>();
    java.util.Collections.addAll(contents, player.getInventory().getContents());
    java.util.Collections.addAll(contents, player.getInventory().getArmorContents());
    ItemStack offHand = player.getInventory().getItemInOffHand();
    if (offHand != null) contents.add(offHand);
    return deps.itemStacks().scan(contents.toArray(ItemStack[]::new));
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

  private record SettlementKey(RaidGameId gameId, UUID playerId) {}

  private record PendingExtraction(
      RaidGameId gameId,
      UUID playerId,
      ExtractionPointId pointId,
      List<ItemInstance> carried,
      CompletableFuture<RaidInventoryOutcome> completion) {

    private PendingExtraction {
      carried = List.copyOf(carried);
    }
  }
}
