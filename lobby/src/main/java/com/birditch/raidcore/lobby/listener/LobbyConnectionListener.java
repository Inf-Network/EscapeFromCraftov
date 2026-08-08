package com.birditch.raidcore.lobby.listener;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.lobby.CosmeticApplier;
import com.birditch.raidcore.lobby.LobbyVisibility;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/// Manages the Hypixel-style lobby join/quit flow: cleared inventory with the
/// Game Menu compass in hotbar slot 4, tab header/footer, welcome message and
/// asynchronous settings/cosmetics loading that hops back to the main thread
/// before touching the player.
public final class LobbyConnectionListener implements Listener {

  private static final int COMPASS_HOTBAR_SLOT = 4;
  private static final String COMPASS_NAME_KEY = "lobby.compass-name";

  private final ConnectionDeps deps;

  public LobbyConnectionListener(ConnectionDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    player.getInventory().clear();
    player.getInventory().setItem(COMPASS_HOTBAR_SLOT, buildCompass());
    player.sendPlayerListHeaderAndFooter(
        deps.messages().raw("tab.header"), deps.messages().raw("tab.footer"));
    player.sendMessage(deps.messages().raw("join.welcome"));
    UUID playerId = player.getUniqueId();
    CompletableFuture.allOf(deps.settings().load(playerId), deps.cosmetics().load(playerId))
        .whenComplete((_, error) ->
            deps.scheduler().runSync(() -> applyLoadedState(player, error)));
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    deps.matchmaking().dequeue(playerId);
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
    if (item == null || item.getType() != Material.COMPASS) {
      return;
    }
    event.setCancelled(true);
    deps.menuOpener().accept(event.getPlayer());
  }

  private ItemStack buildCompass() {
    ItemStack compass = new ItemStack(Material.COMPASS);
    compass.editMeta(meta -> meta.itemName(deps.messages().raw(COMPASS_NAME_KEY)));
    return compass;
  }

  /// Runs on the main thread once settings and cosmetics finished loading; a
  /// load failure is logged and the defaults still apply so the player is
  /// never left half-initialized.
  private void applyLoadedState(Player player, @Nullable Throwable error) {
    if (error != null) {
      deps.logger().log(Level.SEVERE,
          "Failed to load lobby data for " + player.getName(), error);
    }
    if (!player.isOnline()) {
      return;
    }
    CosmeticApplier.apply(player,
        deps.cosmetics().equipped(player.getUniqueId()).orElse(null));
    LobbyVisibility.apply(deps.plugin(), deps.settings(), player);
  }
}
