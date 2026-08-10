package com.birditch.raidcore.lobby.listener;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.lobby.CosmeticApplier;
import com.birditch.raidcore.lobby.LobbyVisibility;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/// Manages the Hypixel-style lobby join/quit flow: cleared inventory with the
/// Game Menu compass in hotbar slot 4, tab header/footer, welcome message and
/// asynchronous settings/cosmetics loading that hops back to the main thread
/// before touching the player.
public final class LobbyConnectionListener implements Listener {

  private static final int COMPASS_HOTBAR_SLOT = 4;
  private static final String COMPASS_NAME_KEY = "lobby.compass-name";

  private final ConnectionDeps deps;
  private final NamespacedKey compassKey;

  public LobbyConnectionListener(ConnectionDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
    this.compassKey = new NamespacedKey(deps.plugin(), "lobby_menu");
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    UUID playerId = player.getUniqueId();
    deps.dataGate().clear(playerId);
    player.getInventory().clear();
    player.sendPlayerListHeaderAndFooter(
        deps.messages().raw("tab.header"), deps.messages().raw("tab.footer"));
    CompletableFuture<Integer> recovered = deps.inventories().purgeOrphanedRaidItems(playerId);
    CompletableFuture.allOf(
            deps.profiles().load(playerId, player.getName()),
            deps.settings().load(playerId),
            deps.cosmetics().load(playerId),
            recovered)
        .whenComplete((_, error) ->
            deps.scheduler().runSync(() -> completeLoad(player, recovered, error)));
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    deps.matchmaking().dequeue(playerId);
    boolean wasReady = deps.dataGate().isReady(playerId);
    deps.dataGate().clear(playerId);
    if (wasReady) {
      deps.profiles().unload(playerId).whenComplete((_, error) -> {
        if (error != null) {
          deps.logger().log(Level.SEVERE, "保存大厅玩家档案失败:" + playerId, error);
        }
      });
    } else {
      deps.profiles().discard(playerId);
    }
    deps.settings().unload(playerId);
    deps.cosmetics().unload(playerId);
  }

  @EventHandler
  public void onInteract(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_AIR
        && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
      return;
    }
    if (event.getHand() != EquipmentSlot.HAND) {
      return;
    }
    ItemStack item = event.getItem();
    if (item == null || item.getType() != Material.COMPASS
        || !item.getPersistentDataContainer().has(compassKey, PersistentDataType.BYTE)) {
      return;
    }
    event.setCancelled(true);
    Player player = event.getPlayer();
    if (deps.dataGate().isReady(player.getUniqueId())) {
      deps.menuOpener().accept(player);
    } else {
      player.sendMessage(deps.messages().raw("queue.data-loading"));
    }
  }

  private ItemStack buildCompass() {
    ItemStack compass = new ItemStack(Material.COMPASS);
    compass.editMeta(meta -> {
      meta.itemName(deps.messages().raw(COMPASS_NAME_KEY));
      meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
    });
    return compass;
  }

  /// Runs on the main thread once settings and cosmetics finished loading; a
  /// load failure is logged and the defaults still apply so the player is
  /// never left half-initialized. When the player already left, the freshly
  /// cached entries are unloaded so a stale async load never leaks.
  private void completeLoad(Player player, CompletableFuture<Integer> recovered,
      @Nullable Throwable error) {
    UUID playerId = player.getUniqueId();
    if (!player.isOnline()) {
      deps.profiles().discard(playerId);
      deps.settings().unload(playerId);
      deps.cosmetics().unload(playerId);
      return;
    }
    if (error != null) {
      deps.logger().log(Level.SEVERE, "加载大厅玩家数据失败:" + player.getName(), error);
      deps.profiles().discard(playerId);
      deps.settings().unload(playerId);
      deps.cosmetics().unload(playerId);
      player.kick(deps.messages().raw("join.data-failed"));
      return;
    }
    int removed = recovered.join();
    if (removed > 0) {
      deps.logger().info("已为 " + player.getName() + " 清理 " + removed
          + " 件未完成行动中的遗留物品");
    }
    deps.dataGate().markReady(playerId);
    player.getInventory().setItem(COMPASS_HOTBAR_SLOT, buildCompass());
    player.sendMessage(deps.messages().raw("join.welcome"));
    CosmeticApplier.apply(player, deps.cosmetics().equipped(playerId).orElse(null));
    LobbyVisibility.apply(deps.plugin(), deps.settings(), player);
  }
}
